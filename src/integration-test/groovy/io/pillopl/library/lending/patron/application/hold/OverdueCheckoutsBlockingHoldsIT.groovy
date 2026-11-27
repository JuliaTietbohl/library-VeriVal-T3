package io.pillopl.library.lending.patron.application.hold

import io.pillopl.library.catalogue.BookId
import io.pillopl.library.commons.commands.Result
import io.pillopl.library.lending.LendingTestContext
import io.pillopl.library.lending.book.model.AvailableBook
import io.pillopl.library.lending.book.model.Book
import io.pillopl.library.lending.book.model.BookRepository
import io.pillopl.library.lending.dailysheet.model.DailySheet
import io.pillopl.library.lending.librarybranch.model.LibraryBranchId
import io.pillopl.library.lending.patron.model.HoldDuration
import io.pillopl.library.lending.patron.model.Patron
import io.pillopl.library.lending.patron.model.PatronId
import io.pillopl.library.lending.patron.model.PatronType
import io.pillopl.library.lending.patron.model.Patrons
import io.vavr.control.Option
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Unroll

import javax.sql.DataSource
import java.time.Duration
import java.time.Instant

import static io.pillopl.library.catalogue.BookType.Circulating
import static io.pillopl.library.lending.book.model.BookFixture.anyBookId
import static io.pillopl.library.lending.book.model.BookFixture.circulatingAvailableBookAt
import static io.pillopl.library.lending.librarybranch.model.LibraryBranchFixture.anyBranch
import static io.pillopl.library.lending.patron.model.PatronEvent.BookCheckedOut
import static io.pillopl.library.lending.patron.model.PatronEvent.OverdueCheckoutRegistered
import static io.pillopl.library.lending.patron.model.PatronEvent.PatronCreated
import static io.pillopl.library.lending.patron.model.PatronFixture.anyPatronId

/**
 * Journey: a patron with overdue checkouts is blocked from placing new holds.
 * Integration level: real Spring context, real (H2) database, real repositories,
 * real read model and real event propagation between aggregates.
 */
@SpringBootTest(classes = LendingTestContext.class)
class OverdueCheckoutsBlockingHoldsIT extends Specification {

    static final String OVERDUE_REASON = "cannot place on hold when there are overdue checkouts"

    PatronId patronId = anyPatronId()
    LibraryBranchId branch = anyBranch()

    @Autowired
    Patrons patronRepo

    @Autowired
    PlacingOnHold placingOnHold

    @Autowired
    BookRepository bookRepository

    @Autowired
    DailySheet dailySheet

    @Autowired
    DataSource dataSource

    @Unroll
    def '#type patron with #overdues overdue checkout(s) is persisted and #outcome when placing a new hold'() {
        given:
            patronRepo.publish(PatronCreated.now(patronId, type))
        and:
            overdues.times { patronRepo.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), branch)) }
        when: 'the patron is reloaded from the database'
            Patron patron = patronRepo.findBy(patronId).get()
        and:
            def result = patron.placeOnHold(circulatingAvailableBookAt(branch), HoldDuration.closeEnded(3))
        then:
            result.isRight() == accepted
            accepted || result.getLeft().reason == OVERDUE_REASON
        where:
            type                | overdues || accepted | outcome
            PatronType.Regular    | 0        || true     | 'accepted'
            PatronType.Regular    | 1        || true     | 'accepted'
            PatronType.Regular    | 2        || false    | 'rejected'
            PatronType.Regular    | 3        || false    | 'rejected'
            PatronType.Researcher | 1        || true     | 'accepted'
            PatronType.Researcher | 2        || false    | 'rejected'
    }

    def 'overdue checkouts at another branch do not block holds at this branch after persistence'() {
        given:
            LibraryBranchId otherBranch = anyBranch()
            patronRepo.publish(PatronCreated.now(patronId, PatronType.Regular))
        and:
            2.times { patronRepo.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), otherBranch)) }
        when:
            Patron patron = patronRepo.findBy(patronId).get()
        then:
            patron.placeOnHold(circulatingAvailableBookAt(branch), HoldDuration.closeEnded(3)).isRight()
            patron.placeOnHold(circulatingAvailableBookAt(otherBranch), HoldDuration.closeEnded(3)).isLeft()
    }

    def 'registering the same overdue checkout twice counts it only once'() {
        given:
            patronRepo.publish(PatronCreated.now(patronId, PatronType.Regular))
            BookId overdueBook = anyBookId()
        when: 'the scheduled job runs twice and publishes the same overdue checkout twice'
            2.times { patronRepo.publish(OverdueCheckoutRegistered.now(patronId, overdueBook, branch)) }
        then: 'a single overdue book is below the limit of 2, so the patron can still place a hold'
            patronRepo.findBy(patronId).get()
                    .placeOnHold(circulatingAvailableBookAt(branch), HoldDuration.closeEnded(3)).isRight()
    }

    @PendingFeature(reason = 'PatronDatabaseEntity has no case for BookHoldFailed, so a rejected hold throws io.vavr.MatchError instead of returning Result.Rejection')
    def 'full flow: hold, checkout, overdue detected by the daily sheet, then the next hold is rejected'() {
        given: 'a regular patron and three books available in the same branch'
            patronRepo.publish(PatronCreated.now(patronId, PatronType.Regular))
            AvailableBook book1 = availableBook()
            AvailableBook book2 = availableBook()
            AvailableBook book3 = availableBook()

        when: 'the patron places on hold and checks out two books, with due dates already in the past'
            assert placingOnHold.placeOnHold(command(book1)).get() == Result.Success
            assert placingOnHold.placeOnHold(command(book2)).get() == Result.Success
            patronRepo.publish(checkedOutYesterday(book1))
            patronRepo.publish(checkedOutYesterday(book2))

        then: 'the daily sheet reports both checkouts as overdue for this patron'
            List<OverdueCheckoutRegistered> overdue = overdueCheckoutsOfPatron()
            overdue.size() == 2
            overdue.collect { it.bookId } as Set == [book1.bookId.bookId, book2.bookId.bookId] as Set

        when: 'the overdue checkouts are registered on the patron'
            overdue.each { patronRepo.publish(it) }

        and: 'the patron tries to place on hold a third book'
            Result result = placingOnHold.placeOnHold(command(book3)).get()

        then: 'the hold is rejected'
            result == Result.Rejection

        and: 'the third book is still available and no hold exists for it'
            Book stored = bookRepository.findBy(book3.bookId).get()
            stored instanceof AvailableBook
            countHoldsOf(book3.bookId) == 0

        and: 'the patron has no active hold'
            patronRepo.findBy(patronId).get().numberOfHolds() == 0
    }

    @PendingFeature(reason = 'PatronDatabaseEntity has no case for BookHoldFailed, so a rejected hold throws io.vavr.MatchError instead of returning Result.Rejection')
    def 'an overdue checkout of a patron does not affect other patrons in the same branch'() {
        given:
            PatronId otherPatron = anyPatronId()
            patronRepo.publish(PatronCreated.now(patronId, PatronType.Regular))
            patronRepo.publish(PatronCreated.now(otherPatron, PatronType.Regular))
            AvailableBook book = availableBook()
        and:
            2.times { patronRepo.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), branch)) }
        when:
            Result blocked = placingOnHold.placeOnHold(commandFor(patronId, book)).get()
            Result free = placingOnHold.placeOnHold(commandFor(otherPatron, book)).get()
        then:
            blocked == Result.Rejection
            free == Result.Success
    }

    // ---- helpers -------------------------------------------------------------------------------

    private AvailableBook availableBook() {
        AvailableBook book = circulatingAvailableBookAt(branch)
        bookRepository.save(book)
        return book
    }

    private PlaceOnHoldCommand command(AvailableBook book) {
        return commandFor(patronId, book)
    }

    private PlaceOnHoldCommand commandFor(PatronId patron, AvailableBook book) {
        return new PlaceOnHoldCommand(Instant.now(), patron, branch, book.bookId, Option.of(3))
    }

    private BookCheckedOut checkedOutYesterday(AvailableBook book) {
        return new BookCheckedOut(
                Instant.now().minus(Duration.ofDays(10)),
                patronId.patronId,
                book.bookId.bookId,
                Circulating,
                branch.libraryBranchId,
                Instant.now().minus(Duration.ofDays(1)))
    }

    private List<OverdueCheckoutRegistered> overdueCheckoutsOfPatron() {
        return dailySheet.queryForCheckoutsToOverdue()
                .toStreamOfEvents()
                .toJavaList()
                .findAll { it.patronId == patronId.patronId }
    }

    private int countHoldsOf(BookId bookId) {
        return new JdbcTemplate(dataSource).query(
                "select count(*) from holds_sheet s where s.book_id = ?",
                [bookId.bookId] as Object[],
                new ColumnMapRowMapper()).get(0).get("COUNT(*)") as int
    }
}
