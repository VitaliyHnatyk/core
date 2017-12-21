package com.github.gvolpe.smartbackpacker.scraper.parser

import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.github.gvolpe.smartbackpacker.model._
import com.github.gvolpe.smartbackpacker.scraper.config.ScraperConfiguration
import com.github.gvolpe.smartbackpacker.scraper.model._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.model.{Document, Element}
import net.ruippeixotog.scalascraper.scraper.HtmlExtractor

import scala.util.{Success, Try}

class HealthInfoParser[F[_]](scraperConfig: ScraperConfiguration[F])
                            (implicit F: Sync[F]) extends AbstractHealthInfoParser[F] {

  private val baseUrl = "https://wwwnc.cdc.gov/travel/destinations/traveler/none"

  override def htmlDocument(from: CountryCode): F[Document] = {
    val ifEmpty = F.raiseError[Document](HealthPageNotFound(from.value))

    scraperConfig.healthPage(from) flatMap { maybeHealthPage =>
      maybeHealthPage.fold(ifEmpty) { healthPage =>
        F.delay {
          val browser = new JsoupBrowser()
          browser.get(s"$baseUrl/$healthPage")
        }
      }
    }
  }

}

abstract class AbstractHealthInfoParser[F[_]: Sync] {

  def htmlDocument(from: CountryCode): F[Document]

  private def extractWebLink(v: String): String = {
    v.split('(').tail.headOption.getOrElse(" ").dropRight(1)
  }

  private def removeBracketsAndWebLink(v: String): String = {
    v.split('(').headOption.getOrElse(v)
  }

  private val travelNoticeExtractor: HtmlExtractor[Element, Iterable[HealthAlert]] = _.flatMap { e =>
    val listBlock: List[Element] = e >> elementList(".list-block li")
    val titleAndLink  = listBlock.map(_ >> text("a")).mkString("")
    val title         = removeBracketsAndWebLink(titleAndLink)
    val link          = extractWebLink(titleAndLink)

    val summary: String =
      (for {
        e     <- listBlock
        elems <- e >> elementList("span")
      } yield {
        Try(elems.attr("class")) match {
          case Success(x) if x == "summary" => elems >> text
          case _ => ""
        }
      }).mkString("")

    Seq(HealthAlert(title, link.as[WebLink], summary))
  }

  private val healthTableExtractor: HtmlExtractor[Element, Iterable[HealthInfoRow]] = _.flatMap { e =>
    Try(e.attr("class")) match {
      case Success(x) if x == "group-head"  =>
        val value = e >> text
        if (value.contains("Most travelers")) Seq(MostTravelers)
        else if (value.contains("Some travelers")) Seq(SomeTravelers)
        else if (value.contains("All travelers")) Seq(AllTravelers)
        else Seq.empty[HealthInfoRow]
      case Success(x) if x == "traveler-disease" =>
        val value = e >> text
        Seq(DiseaseName(removeBracketsAndWebLink(value)))
      case Success(x) if x == "traveler-findoutwhy" =>
        Seq(DiseaseDescription(e >> text))
      case Success(x) if x == "traveler-protect" =>
        val elems = e >> elementList("span")
        val categories: List[DiseaseCategory] = elems.map { ee =>
          Try(ee.attr("class")) match {
            case Success(y) if y == "tooltip-13"  => AvoidNonSterileEquipment
            case Success(y) if y == "tooltip-12"  => TakeAntimalarialMeds
            case Success(y) if y == "tooltip-11"  => GetVaccinated
            case Success(y) if y == "tooltip-9"   => AvoidSharingBodyFluids
            case Success(y) if y == "tooltip-8"   => ReduceExposureToGerms
            case Success(y) if y == "tooltip-4"   => PreventBugBites
            case Success(y) if y == "tooltip-3"   => EatAndDrinkSafely
            case Success(y) if y == "tooltip-1"   => KeepAwayFromAnimals
            case _                                => UnknownDiseaseCategory
          }
        }
        Seq(DiseaseCategories(categories))
      case _                                =>
        Seq.empty[HealthInfoRow]
    }
  }

  def parse(from: CountryCode): F[Health] =
    htmlDocument(from) map { doc =>
      // Vaccines and Medicines section
      val vaccinesTable: List[Element] = doc >> elementList("#dest-vm-a tbody")
      val rows = vaccinesTable.flatMap(_ >> elementList("tr"))
      val cols = rows.flatMap(_ >> extractor("td", healthTableExtractor))

      // Span the three different sections all, most and some
      val (all, mostAndSome)  = cols.span(_ != MostTravelers)
      val (most, some)        = mostAndSome.span(_ != SomeTravelers)

      // Drop category and group per disease
      val mandatory: List[Vaccine] = all.tailOrEmpty.grouped(3).toList.collect {
        case (DiseaseName(n) :: DiseaseDescription(d) :: List(DiseaseCategories(c))) if n != "Routine vaccines" => Vaccine(n.as[Disease], d, c)
      }

      val recommended: List[Vaccine] = most.tailOrEmpty.grouped(3).toList.collect {
        case (DiseaseName(n) :: DiseaseDescription(d) :: List(DiseaseCategories(c))) => Vaccine(n.as[Disease], d, c)
      }

      val optional: List[Vaccine] = some.tailOrEmpty.grouped(3).toList.collect {
        case (DiseaseName(n) :: DiseaseDescription(d) :: List(DiseaseCategories(c))) => Vaccine(n.as[Disease], d, c)
      }

      // Health Travel Notices section
      val travelNoticeTable: List[Element] = doc >> elementList("#travel-notices")
      val section: List[Element] = travelNoticeTable.flatMap(_ >> elementList(".section_body"))

      val alertLevel: AlertLevel = section.map(_ >?> text("h4")).headOption.flatten match {
        case Some(x) if x.contains("Level 1") => LevelOne
        case Some(x) if x.contains("Level 2") => LevelTwo
        case _                                => NoAlert
      }

      val alerts: List[HealthAlert] = section.flatMap(_ >> extractor("ul", travelNoticeExtractor))

      Health(
        vaccinations = Vaccinations(mandatory, recommended, optional),
        notices = HealthNotices(
          alertLevel = alertLevel,
          alerts = alerts
        )
      )
    }

}
