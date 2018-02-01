package lila.mod

import org.joda.time.DateTime
import reactivemongo.api.ReadPreference
import scala.concurrent.duration._

import lila.db.dsl._
import lila.game.BSONHandlers._
import lila.game.{ Game, GameRepo, Query }
import lila.rating.PerfType
import lila.user.{ User, UserRepo }
import lila.report.{ Suspect, Victim }

private final class RatingRefund(
    scheduler: lila.common.Scheduler,
    notifier: ModNotifier,
    historyApi: lila.history.HistoryApi,
    rankingApi: lila.user.RankingApi,
    wasUnengined: Suspect => Fu[Boolean]
) {

  import RatingRefund._

  def schedule(sus: Suspect): Unit = scheduler.once(delay)(apply(sus))

  private def apply(sus: Suspect): Unit = wasUnengined(sus) flatMap {
    case true => funit
    case false =>

      logger.info(s"Refunding ${sus.user.username} victims")

      def lastGames = GameRepo.coll.find(
        Query.win(sus.user.id) ++ Query.rated ++ Query.createdSince(DateTime.now minusDays 3)
      ).sort(Query.sortCreated)
        .cursor[Game](readPreference = ReadPreference.secondaryPreferred)
        .list(30)

      def makeRefunds(games: List[Game]) = games.foldLeft(Refunds(List.empty)) {
        case (refs, g) => (for {
          perf <- g.perfType
          op <- g.playerByUserId(sus.user.id) map g.opponent
          if !op.provisional
          victim <- op.userId
          diff <- op.ratingDiff
          if diff < 0
          rating <- op.rating
        } yield refs.add(victim, perf, -diff, rating)) | refs
      }

      def pointsToRefund(ref: Refund, user: User): Int = {
        ref.diff - user.perfs(ref.perf).intRating + 100 + ref.topRating
      } min ref.diff min 200 max 0

      def refundPoints(victim: Victim, pt: PerfType, points: Int): Funit = {
        val newPerf = victim.user.perfs(pt) refund points
        UserRepo.setPerf(victim.user.id, pt, newPerf) >>
          historyApi.setPerfRating(victim.user, pt, newPerf.intRating) >>
          rankingApi.save(victim.user.id, pt, newPerf) >>
          notifier.refund(victim, pt, points)
      }

      def applyRefund(ref: Refund) =
        UserRepo byId ref.victim flatMap {
          _ ?? { user =>
            val points = pointsToRefund(ref, user)
            logger.info(s"Refunding $ref -> $points")
            (points > 0) ?? refundPoints(Victim(user), ref.perf, points)
          }
        }

      lastGames map makeRefunds flatMap { _.all.map(applyRefund).sequenceFu } void
  }
}

private object RatingRefund {

  val delay = 1 minute

  case class Refund(victim: User.ID, perf: PerfType, diff: Int, topRating: Int) {
    def is(v: User.ID, p: PerfType): Boolean = v == victim && p == perf
    def is(r: Refund): Boolean = is(r.victim, r.perf)
    def add(d: Int, r: Int) = copy(diff = diff + d, topRating = topRating max r)
  }

  case class Refunds(all: List[Refund]) {
    def add(victim: User.ID, perf: PerfType, diff: Int, rating: Int) = copy(all =
      all.find(_.is(victim, perf)) match {
        case None => Refund(victim, perf, diff, rating) :: all
        case Some(r) => r.add(diff, rating) :: all.filterNot(_ is r)
      })
  }
}