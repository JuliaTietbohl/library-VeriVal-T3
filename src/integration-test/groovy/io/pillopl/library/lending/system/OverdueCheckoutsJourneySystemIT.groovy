package io.pillopl.library.lending.system

import groovy.json.JsonOutput
import io.pillopl.library.lending.LendingTestContext
import io.pillopl.library.lending.book.model.AvailableBook
import io.pillopl.library.lending.book.model.Book
import io.pillopl.library.lending.book.model.BookRepository
import io.pillopl.library.lending.dailysheet.model.DailySheet
import io.pillopl.library.lending.librarybranch.model.LibraryBranchId
import io.pillopl.library.lending.patron.model.PatronId
import io.pillopl.library.lending.patron.model.PatronType
import io.pillopl.library.lending.patron.model.Patrons
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import spock.lang.PendingFeature
import spock.lang.Specification

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
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

/**
 * System level tests of the journey "a patron with overdue checkouts is blocked from placing new holds".
 * A real HTTP server on a random port, the whole Spring context, the real database and real event propagation.
 * <p>
 * There is no HTTP endpoint for check out or for registering overdue checkouts, so those steps are triggered
 * through the domain events/read model (the same way the application does it internally).
 */
@SpringBootTest(classes = LendingTestContext.class, webEnvironment = RANDOM_PORT)
class OverdueCheckoutsJourneySystemIT extends Specification {

    PatronId patronId = anyPatronId()
    LibraryBranchId branch = anyBranch()

    @Autowired
    TestRestTemplate http

    @Autowired
    Patrons patrons

    @Autowired
    BookRepository bookRepository

    @Autowired
    DailySheet dailySheet

    def 'TS-01 journey: after two overdue checkouts the patron cannot place a new hold'() {
        given: 'a regular patron and three books available in the same branch'
            patrons.publish(PatronCreated.now(patronId, PatronType.Regular))
            AvailableBook book1 = availableBook(branch)
            AvailableBook book2 = availableBook(branch)
            AvailableBook book3 = availableBook(branch)

        when: 'the patron places two holds through the API'
            def hold1 = placeHold(patronId, book1, branch)
            def hold2 = placeHold(patronId, book2, branch)
        then:
            hold1.statusCode == HttpStatus.OK
            hold2.statusCode == HttpStatus.OK
            getHold(patronId, book1).statusCode == HttpStatus.OK
            getHold(patronId, book2).statusCode == HttpStatus.OK

        when: 'both books are checked out with a due date in the past and the overdue checkouts are registered'
            checkOutAsOverdue(book1)
            checkOutAsOverdue(book2)
            registerOverdueCheckoutsOfPatron()
        then: 'the profile shows the checkouts'
            getCheckout(patronId, book1).statusCode == HttpStatus.OK
            getCheckout(patronId, book2).statusCode == HttpStatus.OK

        when: 'the patron tries to place a third hold'
            placeHold(patronId, book3, branch)
        then: 'the third hold is not created'
            getHold(patronId, book3).statusCode == HttpStatus.NOT_FOUND
        and: 'the book stays available for other patrons'
            bookRepository.findBy(book3.bookId).get() instanceof AvailableBook
    }

    def 'TS-02 a patron with only one overdue checkout can still place a hold'() {
        given:
            patrons.publish(PatronCreated.now(patronId, PatronType.Regular))
            AvailableBook book = availableBook(branch)
        and:
            patrons.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), branch))
        when:
            def response = placeHold(patronId, book, branch)
        then:
            response.statusCode == HttpStatus.OK
            getHold(patronId, book).statusCode == HttpStatus.OK
    }

    def 'TS-03 overdue checkouts in another branch do not block a hold in this branch'() {
        given:
            LibraryBranchId otherBranch = anyBranch()
            patrons.publish(PatronCreated.now(patronId, PatronType.Regular))
            AvailableBook book = availableBook(branch)
        and:
            2.times { patrons.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), otherBranch)) }
        when:
            def response = placeHold(patronId, book, branch)
        then:
            response.statusCode == HttpStatus.OK
            getHold(patronId, book).statusCode == HttpStatus.OK
    }

    def 'TS-04 a researcher patron with two overdue checkouts is blocked as well'() {
        given:
            patrons.publish(PatronCreated.now(patronId, PatronType.Researcher))
            AvailableBook book = availableBook(branch)
        and:
            2.times { patrons.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), branch)) }
        when:
            placeHold(patronId, book, branch)
        then:
            getHold(patronId, book).statusCode == HttpStatus.NOT_FOUND
            bookRepository.findBy(book.bookId).get() instanceof AvailableBook
    }

    def 'TS-05 holds placed before the overdue checkouts are kept and the profile stays accessible'() {
        given:
            patrons.publish(PatronCreated.now(patronId, PatronType.Regular))
            AvailableBook earlierBook = availableBook(branch)
            AvailableBook blockedBook = availableBook(branch)
        and: 'a hold is placed while the patron is still in good standing'
            assert placeHold(patronId, earlierBook, branch).statusCode == HttpStatus.OK
        when: 'the patron becomes blocked'
            2.times { patrons.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), branch)) }
            placeHold(patronId, blockedBook, branch)
        then: 'the earlier hold is untouched'
            getHold(patronId, earlierBook).statusCode == HttpStatus.OK
        and: 'the profile is still available'
            http.getForEntity("/profiles/${patronId.patronId}", String).statusCode == HttpStatus.OK
    }

    def 'TS-06 a blocked patron does not affect other patrons for the same book'() {
        given:
            PatronId other = anyPatronId()
            patrons.publish(PatronCreated.now(patronId, PatronType.Regular))
            patrons.publish(PatronCreated.now(other, PatronType.Regular))
            AvailableBook book = availableBook(branch)
        and:
            2.times { patrons.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), branch)) }
        when:
            placeHold(patronId, book, branch)
            def response = placeHold(other, book, branch)
        then:
            response.statusCode == HttpStatus.OK
            getHold(other, book).statusCode == HttpStatus.OK
    }

    @PendingFeature(reason = 'a hold rejected by the domain is answered with 500 INTERNAL_SERVER_ERROR (MatchError on BookHoldFailed in PatronDatabaseEntity)')
    def 'TS-07 a hold rejected because of overdue checkouts is answered with a client error status'() {
        given:
            patrons.publish(PatronCreated.now(patronId, PatronType.Regular))
            AvailableBook book = availableBook(branch)
        and:
            2.times { patrons.publish(OverdueCheckoutRegistered.now(patronId, anyBookId(), branch)) }
        when:
            def response = placeHold(patronId, book, branch)
        then:
            getHold(patronId, book).statusCode == HttpStatus.NOT_FOUND
            response.statusCode in [HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY]
    }

    // ---- helpers -------------------------------------------------------------------------------

    private AvailableBook availableBook(LibraryBranchId libraryBranchId) {
        AvailableBook book = circulatingAvailableBookAt(libraryBranchId)
        bookRepository.save(book)
        return book
    }

    private ResponseEntity<String> placeHold(PatronId patron, AvailableBook book, LibraryBranchId libraryBranchId) {
        HttpHeaders headers = new HttpHeaders()
        headers.setContentType(MediaType.APPLICATION_JSON)
        String body = JsonOutput.toJson([
                bookId         : book.bookId.bookId.toString(),
                libraryBranchId: libraryBranchId.libraryBranchId.toString(),
                numberOfDays   : 3])
        return http.postForEntity("/profiles/${patron.patronId}/holds", new HttpEntity<>(body, headers), String)
    }

    private ResponseEntity<String> getHold(PatronId patron, AvailableBook book) {
        return http.getForEntity("/profiles/${patron.patronId}/holds/${book.bookId.bookId}", String)
    }

    private ResponseEntity<String> getCheckout(PatronId patron, AvailableBook book) {
        return http.getForEntity("/profiles/${patron.patronId}/checkouts/${book.bookId.bookId}", String)
    }

    private void checkOutAsOverdue(AvailableBook book) {
        patrons.publish(new BookCheckedOut(
                Instant.now().minus(Duration.ofDays(10)),
                patronId.patronId,
                book.bookId.bookId,
                Circulating,
                branch.libraryBranchId,
                Instant.now().minus(Duration.ofDays(1))))
    }

    /** What the scheduled job does, limited to this patron because the database is shared with other tests. */
    private void registerOverdueCheckoutsOfPatron() {
        dailySheet.queryForCheckoutsToOverdue()
                .toStreamOfEvents()
                .toJavaList()
                .findAll { it.patronId == patronId.patronId }
                .each { patrons.publish(it) }
    }
}
