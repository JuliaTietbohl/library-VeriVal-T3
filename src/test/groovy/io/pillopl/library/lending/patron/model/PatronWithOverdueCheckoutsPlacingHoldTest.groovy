package io.pillopl.library.lending.patron.model

import io.pillopl.library.catalogue.BookId
import io.pillopl.library.lending.book.model.AvailableBook
import io.pillopl.library.lending.librarybranch.model.LibraryBranchId
import io.vavr.control.Either
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Unroll

import static io.pillopl.library.lending.book.model.BookFixture.*
import static io.pillopl.library.lending.librarybranch.model.LibraryBranchFixture.anyBranch
import static io.pillopl.library.lending.patron.model.HoldDuration.closeEnded
import static io.pillopl.library.lending.patron.model.HoldDuration.openEnded
import static io.pillopl.library.lending.patron.model.PatronEvent.BookHoldFailed
import static io.pillopl.library.lending.patron.model.PatronEvent.BookPlacedOnHoldEvents
import static io.pillopl.library.lending.patron.model.PatronFixture.anyPatronId
import static io.pillopl.library.lending.patron.model.PatronFixture.patronWithAllPoliciesAndOverdueCheckouts
import static io.pillopl.library.lending.patron.model.PatronType.Regular
import static io.pillopl.library.lending.patron.model.PatronType.Researcher

/**
 * Journey: a patron with overdue checkouts is blocked from placing new holds.
 * Unit level, with all placing-on-hold policies active together
 * (the existing tests exercise one policy at a time).
 */
class PatronWithOverdueCheckoutsPlacingHoldTest extends Specification {

    static final String RESTRICTED = "Regular patrons cannot hold restricted books"
    static final String OVERDUE = "cannot place on hold when there are overdue checkouts"
    static final String MAX_HOLDS = "patron cannot hold more books"
    static final String OPEN_ENDED = "regular patron cannot place open ended holds"

    @Unroll
    def 'decision table: #type, restricted=#restricted, #overdues overdue(s), #holds hold(s), openEnded=#openEndedHold -> #expectedReason'() {
        given:
            LibraryBranchId branch = anyBranch()
            AvailableBook book = restricted ? restrictedBookAt(branch) : circulatingAvailableBookAt(branch)
            Patron patron = patronWithAllPoliciesAndOverdueCheckouts(anyPatronId(), type, [(branch): books(overdues)], holds)
        when:
            Either<BookHoldFailed, BookPlacedOnHoldEvents> result =
                    patron.placeOnHold(book, openEndedHold ? openEnded() : closeEnded(3))
        then:
            if (expectedReason == null) {
                assert result.isRight()
            } else {
                assert result.isLeft()
                assert result.getLeft().reason == expectedReason
            }
        where:
            type       | restricted | overdues | holds | openEndedHold || expectedReason
            // overdue limit (boundary 1 / 2 / 3) for both patron types
            Regular    | false      | 1        | 0     | false         || null
            Regular    | false      | 2        | 0     | false         || OVERDUE
            Researcher | false      | 1        | 0     | false         || null
            Researcher | false      | 2        | 0     | false         || OVERDUE
            Researcher | false      | 3        | 0     | false         || OVERDUE
            // restricted books: regular is stopped by the restricted policy first, researcher by the overdue one
            Regular    | true       | 2        | 0     | false         || RESTRICTED
            Researcher | true       | 2        | 0     | false         || OVERDUE
            Researcher | true       | 1        | 0     | false         || null
            // other policies still work when the patron has a single overdue
            Regular    | false      | 1        | 5     | false         || MAX_HOLDS
            Regular    | false      | 1        | 4     | false         || null
            Regular    | false      | 1        | 0     | true          || OPEN_ENDED
            Researcher | false      | 1        | 5     | true          || null
            // several rules broken at once: the first policy of the list wins
            Regular    | false      | 2        | 5     | true          || OVERDUE
            Regular    | true       | 2        | 5     | true          || RESTRICTED
    }

    @Unroll
    def 'overdue checkouts are counted per branch: #overdueAtB at B and #overdueAtC at C'() {
        given:
            LibraryBranchId branchB = anyBranch()
            LibraryBranchId branchC = anyBranch()
            Patron patron = patronWithAllPoliciesAndOverdueCheckouts(
                    anyPatronId(), Regular, [(branchB): books(overdueAtB), (branchC): books(overdueAtC)], 0)
        expect:
            patron.placeOnHold(circulatingAvailableBookAt(branchB), closeEnded(3)).isRight() == acceptedAtB
            patron.placeOnHold(circulatingAvailableBookAt(branchC), closeEnded(3)).isRight() == acceptedAtC
        where:
            overdueAtB | overdueAtC || acceptedAtB | acceptedAtC
            1          | 1          || true        | true   // 2 overdues in total, but only 1 per branch
            2          | 1          || false       | true
            1          | 2          || true        | false
            2          | 2          || false       | false
    }

    def 'rejection caused by overdue checkouts reports who, what and where'() {
        given:
            LibraryBranchId branch = anyBranch()
            AvailableBook book = circulatingAvailableBookAt(branch)
            def patronId = anyPatronId()
            Patron patron = patronWithAllPoliciesAndOverdueCheckouts(patronId, Regular, [(branch): books(2)], 0)
        when:
            Either<BookHoldFailed, BookPlacedOnHoldEvents> result = patron.placeOnHold(book, closeEnded(3))
        then:
            result.isLeft()
            with(result.getLeft()) {
                reason == OVERDUE
                it.patronId == patronId.patronId
                it.bookId == book.bookId.bookId
                it.libraryBranchId == branch.libraryBranchId
            }
        and: 'a rejected hold does not change the patron'
            patron.numberOfHolds() == 0
    }

    def 'a patron below the overdue limit gets the hold confirmed for the requested book and branch'() {
        given:
            LibraryBranchId branch = anyBranch()
            AvailableBook book = circulatingAvailableBookAt(branch)
            def patronId = anyPatronId()
            Patron patron = patronWithAllPoliciesAndOverdueCheckouts(patronId, Regular, [(branch): books(1)], 0)
        when:
            Either<BookHoldFailed, BookPlacedOnHoldEvents> result = patron.placeOnHold(book, closeEnded(3))
        then:
            result.isRight()
            with(result.get().bookPlacedOnHold) {
                it.patronId == patronId.patronId
                it.bookId == book.bookId.bookId
                it.libraryBranchId == branch.libraryBranchId
            }
    }

    @PendingFeature(reason = 'README says "more than two overdue checkouts" but PlacingOnHoldPolicy rejects at two (>= MAX_COUNT_OF_OVERDUE_RESOURCES)')
    def 'TU-05 README specification: a patron with exactly two overdue checkouts at a branch can still place a hold'() {
        given:
            LibraryBranchId branch = anyBranch()
            Patron patron = patronWithAllPoliciesAndOverdueCheckouts(anyPatronId(), Regular, [(branch): books(2)], 0)
        expect:
            patron.placeOnHold(circulatingAvailableBookAt(branch), closeEnded(3)).isRight()
    }

    private static Set<BookId> books(int howMany) {
        (1..howMany).collect { anyBookId() } as Set
    }
}
