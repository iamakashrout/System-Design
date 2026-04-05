import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * COMPREHENSIVE EXAMPLE — Library Management System
 *
 * This file brings together EVERY concept from the Object Modeling module:
 *
 *   Step 1 — Entity identification and responsibility assignment
 *             Each class knows what it knows and does what it should do.
 *             Logic lives closest to the data it needs (Information Expert).
 *
 *   Step 2 — All four relationships used correctly:
 *             Composition   : Library → Book, Library → Member, Book → BookCopy,
 *                             Member → Borrowing
 *             Association   : Borrowing → Member, Borrowing → BookCopy,
 *                             BookCopy → Book (back-reference)
 *             Aggregation   : (shown in comments — e.g., if the system had Tags)
 *             Dependency    : BorrowingService depends on nothing it doesn't need
 *
 *   Step 3 — Composition over inheritance where appropriate.
 *             No forced IS-A relationships.
 *
 *   Step 4 — Clean API design throughout:
 *             Rule 1: Tell Don't Ask — markBorrowed(), confirm(), hasReachedBorrowLimit()
 *             Rule 2: Rich return types — Optional<Book>, Optional<Member>
 *             Rule 3: Fail fast — validation at every boundary, named exceptions
 *
 * SYSTEM OVERVIEW:
 *   A library holds Books. Each Book has one or more BookCopies (physical copies).
 *   Members can borrow available BookCopies.
 *   Each Borrowing records who borrowed which copy, when, and the due date.
 *   If a copy is returned late, the Borrowing calculates the fine.
 *   BorrowingService orchestrates the borrow and return operations.
 *   Library is the aggregate root — all access flows through it.
 */

// ═══════════════════════════════════════════════════════════════════════════════
// CUSTOM EXCEPTIONS — Domain-specific, named, meaningful
// ═══════════════════════════════════════════════════════════════════════════════

class BookNotFoundException extends RuntimeException {
    public BookNotFoundException(String isbn) {
        super("No book found with ISBN: " + isbn);
    }
}

class MemberNotFoundException extends RuntimeException {
    public MemberNotFoundException(String memberId) {
        super("No member found with ID: " + memberId);
    }
}

class BookNotAvailableException extends RuntimeException {
    public BookNotAvailableException(String bookTitle) {
        super("No available copies for: '" + bookTitle + "'");
    }
}

class BorrowLimitExceededException extends RuntimeException {
    public BorrowLimitExceededException(String memberName, int limit) {
        super("Member '" + memberName + "' has reached the borrow limit of " + limit);
    }
}

class BorrowingNotFoundException extends RuntimeException {
    public BorrowingNotFoundException(String borrowingId) {
        super("No borrowing found with ID: " + borrowingId);
    }
}

class AlreadyReturnedException extends RuntimeException {
    public AlreadyReturnedException(String borrowingId) {
        super("Borrowing " + borrowingId + " has already been returned");
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LAYER 1: VALUE OBJECT — ISBN
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * ISBN is a VALUE OBJECT — not an entity.
 *
 * Why a value object instead of a plain String?
 *   - Validation is enforced at the type level (Fail Fast, Rule 3).
 *   - Invalid ISBNs are rejected BEFORE they enter any business logic.
 *   - Equality is by VALUE, not identity — two ISBN("9780134685991") are equal.
 *   - public Book addBook(ISBN isbn) is safer than public Book addBook(String isbn).
 *
 * Characteristics of a value object:
 *   - final class (cannot be subclassed)
 *   - all fields final (immutable after construction)
 *   - equals() and hashCode() based on value, not reference
 *   - no identity of its own (it IS its value)
 */
final class ISBN {
    private final String value;

    public ISBN(String value) {
        // FAIL FAST: invalid ISBNs are rejected right here, at the boundary
        Objects.requireNonNull(value, "ISBN value cannot be null");
        if (!value.matches("\\d{13}")) {
            throw new IllegalArgumentException(
                    "Invalid ISBN format '" + value + "'. Must be exactly 13 digits.");
        }
        this.value = value;
    }

    public String getValue() { return value; }

    /**
     * Value-based equality — two ISBNs with the same digits ARE the same ISBN.
     * This is unlike Entity equality where identity (ID) determines sameness.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ISBN)) return false;
        return value.equals(((ISBN) o).value);
    }

    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return "ISBN[" + value + "]"; }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LAYER 2: CORE ENTITIES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * BookCopy — a physical copy of a book in the library.
 *
 * RELATIONSHIP: Composition with Book (BookCopy has no life outside a Book).
 * RELATIONSHIP: Association back to Book (back-reference for convenience).
 *
 * Tell Don't Ask:
 *   markBorrowed() and markReturned() manage their own state.
 *   Nobody external does: copy.setAvailable(false).
 *   The copy validates its own preconditions.
 *
 * Package-private constructor: ONLY Book can create a BookCopy.
 * This enforces the composition contract at the language level.
 */
class BookCopy {
    private final String copyId;
    private final Book book;       // ASSOCIATION back to its Book (not composition — copy doesn't own book)
    private boolean available;

    /**
     * Package-private: enforces that only Book (within this package) can create copies.
     * No external code can write: new BookCopy(...) — the compiler prevents it.
     */
    BookCopy(String copyId, Book book) {
        this.copyId = copyId;
        this.book = book;
        this.available = true;
    }

    /**
     * TELL DON'T ASK:
     * Caller says "mark yourself borrowed".
     * Copy validates that it IS available before agreeing.
     * If it's already borrowed — copy says no, immediately, with a clear message.
     */
    public void markBorrowed() {
        if (!available) {
            throw new IllegalStateException(
                    "Copy '" + copyId + "' of '" + book.getTitle() + "' is already borrowed");
        }
        this.available = false;
        System.out.println("[BookCopy] Copy " + copyId + " marked as borrowed");
    }

    /**
     * TELL DON'T ASK:
     * Caller says "mark yourself returned".
     * Copy validates that it IS borrowed before agreeing.
     */
    public void markReturned() {
        if (available) {
            throw new IllegalStateException(
                    "Copy '" + copyId + "' is not currently borrowed — cannot mark returned");
        }
        this.available = true;
        System.out.println("[BookCopy] Copy " + copyId + " marked as returned");
    }

    public boolean isAvailable() { return available; }
    public String getId() { return copyId; }
    public Book getBook() { return book; }

    @Override
    public String toString() {
        return "BookCopy{id='" + copyId + "', available=" + available + "}";
    }
}

/**
 * Book — represents a book title in the library catalog.
 *
 * RELATIONSHIP: Composition with BookCopy (Book creates and owns its copies).
 *               If this Book record is removed, its copies go with it.
 *
 * Responsibilities:
 *   - Knows its own ISBN, title, author.
 *   - Creates and manages its physical copies.
 *   - Knows which of its copies are currently available.
 */
class Book {
    private final ISBN isbn;
    private final String title;
    private final String author;

    // COMPOSITION: copies are created INSIDE Book.addCopy() — nowhere else.
    // Book controls the birth of every copy.
    private final List<BookCopy> copies;

    public Book(ISBN isbn, String title, String author) {
        Objects.requireNonNull(isbn, "ISBN cannot be null");
        Objects.requireNonNull(title, "Title cannot be null");
        Objects.requireNonNull(author, "Author cannot be null");

        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.copies = new ArrayList<>();
    }

    /**
     * COMPOSITION in action: BookCopy is created HERE, inside Book.
     * No external code creates BookCopy directly.
     * Book controls what copies exist and tracks them.
     *
     * @param copyId unique identifier for this physical copy
     * @return the newly created BookCopy (returned so caller can reference it if needed)
     */
    public BookCopy addCopy(String copyId) {
        BookCopy copy = new BookCopy(copyId, this); // Book creates BookCopy — composition
        copies.add(copy);
        System.out.println("[Book] Added copy " + copyId + " to '" + title + "'");
        return copy;
    }

    /**
     * Book knows which of its own copies are available.
     * Callers don't filter the list themselves — they ask Book.
     * (Tell Don't Ask principle)
     */
    public List<BookCopy> getAvailableCopies() {
        return copies.stream()
                .filter(BookCopy::isAvailable)
                .collect(Collectors.toList());
    }

    public boolean hasAvailableCopy() {
        return copies.stream().anyMatch(BookCopy::isAvailable);
    }

    public int getTotalCopies() { return copies.size(); }
    public int getAvailableCopyCount() { return getAvailableCopies().size(); }

    public ISBN getIsbn() { return isbn; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }

    @Override
    public String toString() {
        return "Book{isbn=" + isbn + ", title='" + title + "', author='" + author
                + "', copies=" + copies.size() + "}";
    }
}

/**
 * Member — a registered library member.
 *
 * RELATIONSHIP: Composition with Borrowing (active borrowings belong to this member,
 *               they exist because of this member's borrowing activity).
 *
 * TELL DON'T ASK:
 *   hasReachedBorrowLimit() — member knows its own limit, callers don't check .size() >= 5.
 *   addBorrowing() / removeBorrowing() — member controls its own borrowing list.
 *
 * ENCAPSULATION:
 *   getActiveBorrowings() returns an unmodifiable view.
 *   External code cannot do: member.getActiveBorrowings().add(...)
 *   This prevents bypassing addBorrowing() and losing control.
 */
class Member {
    private static final int BORROW_LIMIT = 3; // kept small to demo the limit check

    private final String memberId;
    private final String name;
    private final String email;

    // COMPOSITION: these borrowings exist because this member borrowed books
    private final List<Borrowing> activeBorrowings;

    public Member(String memberId, String name, String email) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(name, "name cannot be null");

        this.memberId = memberId;
        this.name = name;
        this.email = email;
        this.activeBorrowings = new ArrayList<>();
    }

    /**
     * TELL DON'T ASK:
     * Member knows its own borrow limit.
     * Callers ask: member.hasReachedBorrowLimit()
     * Not: if (member.getActiveBorrowings().size() >= 3)
     *
     * If the limit changes, or the rule gets more complex (e.g., "gold members get 10"),
     * only THIS method changes — not every caller.
     */
    public boolean hasReachedBorrowLimit() {
        return activeBorrowings.size() >= BORROW_LIMIT;
    }

    public void addBorrowing(Borrowing borrowing) {
        activeBorrowings.add(borrowing);
    }

    public void removeBorrowing(Borrowing borrowing) {
        activeBorrowings.remove(borrowing);
    }

    /**
     * RICH RETURN + ENCAPSULATION:
     * Returns an unmodifiable view — callers can iterate but not modify.
     * This prevents external code from bypassing the member's control over its list.
     */
    public List<Borrowing> getActiveBorrowings() {
        return Collections.unmodifiableList(activeBorrowings);
    }

    public boolean hasOverdueBooks() {
        return activeBorrowings.stream().anyMatch(Borrowing::isOverdue);
    }

    public String getId() { return memberId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public int getBorrowCount() { return activeBorrowings.size(); }
    public int getBorrowLimit() { return BORROW_LIMIT; }

    @Override
    public String toString() {
        return "Member{id='" + memberId + "', name='" + name
                + "', borrowed=" + activeBorrowings.size() + "/" + BORROW_LIMIT + "}";
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LAYER 3: DOMAIN RECORD — Borrowing
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Borrowing — records a member borrowing a book copy.
 *
 * RELATIONSHIP: Association with Member (borrowing knows who borrowed, but doesn't own the member).
 * RELATIONSHIP: Association with BookCopy (borrowing knows which copy, but doesn't own the copy).
 *
 * WHY calculateFine() LIVES HERE (Information Expert Principle):
 *   Fine calculation needs: dueDate, returnDate.
 *   Both live on Borrowing.
 *   Therefore, Borrowing is the information expert for fine calculation.
 *
 *   If calculateFine() were on Member:
 *     Member would need to reach into Borrowing to get dueDate/returnDate.
 *     That violates Tell Don't Ask — Member would be "asking" Borrowing for its data.
 *
 *   If calculateFine() were on Library:
 *     Library becomes a god class that knows everything about everyone.
 *
 * SMART NULL HANDLING:
 *   calculateFine() works for both active borrowings (returnDate is null → use today)
 *   and returned ones (returnDate is set → use actual return date).
 *   Callers don't need to know which case they're in.
 */
class Borrowing {
    private static final double FINE_PER_DAY = 2.0; // ₹2 per overdue day

    private final String borrowingId;
    private final Member member;     // ASSOCIATION — Borrowing knows its Member, doesn't own it
    private final BookCopy copy;     // ASSOCIATION — Borrowing knows its copy, doesn't own it
    private final LocalDate borrowDate;
    private final LocalDate dueDate;
    private LocalDate returnDate;    // null until book is returned

    public Borrowing(Member member, BookCopy copy, LocalDate borrowDate, LocalDate dueDate) {
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(copy, "copy cannot be null");

        this.borrowingId = "BRW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.member = member;
        this.copy = copy;
        this.borrowDate = borrowDate;
        this.dueDate = dueDate;
    }

    /**
     * Fine calculation — lives here because dueDate and returnDate live here.
     *
     * Handles both cases:
     *   - Book still borrowed (returnDate == null): calculates fine accrued up to TODAY.
     *     Useful for showing a member "you owe ₹X if you return today."
     *   - Book returned (returnDate != null): calculates final fine at time of return.
     */
    public double calculateFine() {
        LocalDate effectiveDate = (returnDate != null) ? returnDate : LocalDate.now();
        long overdueDays = ChronoUnit.DAYS.between(dueDate, effectiveDate);
        return overdueDays > 0 ? overdueDays * FINE_PER_DAY : 0.0;
    }

    /**
     * TELL DON'T ASK + FAIL FAST:
     * Borrowing validates that it hasn't already been returned.
     * External code doesn't check isReturned() before calling — just calls this.
     */
    public void markReturned(LocalDate returnDate) {
        if (this.returnDate != null) {
            throw new AlreadyReturnedException(borrowingId);
        }
        this.returnDate = returnDate;
        System.out.println("[Borrowing] " + borrowingId + " marked returned on " + returnDate);
    }

    /**
     * A borrowing is overdue if: not yet returned AND today is after the due date.
     * This logic belongs on Borrowing — it knows both returnDate and dueDate.
     */
    public boolean isOverdue() {
        return returnDate == null && LocalDate.now().isAfter(dueDate);
    }

    public boolean isReturned() { return returnDate != null; }

    public String getId() { return borrowingId; }
    public Member getMember() { return member; }
    public BookCopy getCopy() { return copy; }
    public LocalDate getBorrowDate() { return borrowDate; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getReturnDate() { return returnDate; }

    @Override
    public String toString() {
        return "Borrowing{id='" + borrowingId
                + "', member='" + member.getName()
                + "', book='" + copy.getBook().getTitle()
                + "', due=" + dueDate
                + (returnDate != null ? ", returned=" + returnDate : ", ACTIVE")
                + "}";
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LAYER 5: AGGREGATE ROOT — Library
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Library — the aggregate root of the entire system.
 *
 * AGGREGATE ROOT means:
 *   This is the single entry point for all access to books and members.
 *   External code does NOT access Book or Member objects directly from a repository.
 *   All creation, lookup, and modification flows through Library.
 *
 * RELATIONSHIPS:
 *   COMPOSITION with Book: Library creates Books. If Library closes, Books cease to exist.
 *   COMPOSITION with Member: Library creates Members. Members exist within this library context.
 *
 * RICH RETURN TYPES (Rule 2):
 *   findBook() returns Optional<Book> — makes "not found" explicit in the return type.
 *   findMember() returns Optional<Member> — same pattern.
 *   Callers are forced to handle the empty case — no hidden null surprises.
 */
class Library {
    private final String libraryName;
    private final Map<ISBN, Book> books;       // COMPOSITION: Library creates Books
    private final Map<String, Member> members; // COMPOSITION: Library creates Members

    public Library(String libraryName) {
        this.libraryName = libraryName;
        this.books = new HashMap<>();
        this.members = new HashMap<>();
        System.out.println("[Library] '" + libraryName + "' initialized");
    }

    /**
     * COMPOSITION: Book is created HERE — inside Library.
     * No external code creates Book and passes it in.
     * Library controls what books exist in the catalog.
     */
    public Book addBook(ISBN isbn, String title, String author) {
        if (books.containsKey(isbn)) {
            throw new IllegalArgumentException("Book with ISBN " + isbn + " already exists");
        }
        Book book = new Book(isbn, title, author); // Library creates Book — composition
        books.put(isbn, book);
        System.out.println("[Library] Added book: '" + title + "' by " + author);
        return book;
    }

    /**
     * COMPOSITION: Member is created HERE — inside Library.
     */
    public Member registerMember(String memberId, String name, String email) {
        if (members.containsKey(memberId)) {
            throw new IllegalArgumentException("Member with ID " + memberId + " already exists");
        }
        Member member = new Member(memberId, name, email); // Library creates Member — composition
        members.put(memberId, member);
        System.out.println("[Library] Registered member: " + name + " (ID: " + memberId + ")");
        return member;
    }

    /**
     * RICH RETURN TYPE (Rule 2):
     * Optional<Book> — the return type itself tells callers "this might be empty."
     * No silent null returns. Callers must handle both cases.
     */
    public Optional<Book> findBook(ISBN isbn) {
        return Optional.ofNullable(books.get(isbn));
    }

    public Optional<Member> findMember(String memberId) {
        return Optional.ofNullable(members.get(memberId));
    }

    public List<Book> getAllBooks() {
        return Collections.unmodifiableList(new ArrayList<>(books.values()));
    }

    public List<Member> getAllMembers() {
        return Collections.unmodifiableList(new ArrayList<>(members.values()));
    }

    public void printCatalog() {
        System.out.println("\n[Library Catalog] '" + libraryName + "' — " + books.size() + " titles:");
        for (Book book : books.values()) {
            System.out.println("   " + book.getTitle() + " by " + book.getAuthor()
                    + " | " + book.getIsbn()
                    + " | Copies: " + book.getAvailableCopyCount()
                    + "/" + book.getTotalCopies() + " available");
        }
    }

    public String getName() { return libraryName; }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LAYER 4: SERVICE LAYER — BorrowingService
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * BorrowingService — orchestrates the borrow and return operations.
 *
 * SINGLE RESPONSIBILITY:
 *   This service does ONE thing: coordinate the steps of borrowing and returning.
 *   It does NOT implement domain rules — it delegates those to domain objects.
 *
 * WHAT IT DOES (orchestration):
 *   1. Find the right objects (member, book, copy)
 *   2. Check preconditions (does the member exist? is a copy available?)
 *   3. Tell domain objects to do their part (copy.markBorrowed, member.addBorrowing)
 *   4. Return a result
 *
 * WHAT IT DOES NOT DO (domain logic):
 *   - Calculate fines (that's Borrowing's job)
 *   - Check borrow limits (that's Member's job)
 *   - Check copy availability (that's BookCopy's job)
 *   - Manage its own copy/member lists (that's Library's job)
 *
 * RELATIONSHIP: Association with Library (injected, not created here).
 *   BorrowingService doesn't OWN the library — it uses it.
 *   This makes testing easy: inject a test library with pre-loaded data.
 */
class BorrowingService {
    private final Library library;                                // ASSOCIATION — injected
    private final Map<String, Borrowing> borrowingRegistry;       // tracks active borrowings

    public BorrowingService(Library library) {
        Objects.requireNonNull(library, "Library cannot be null");
        this.library = library;
        this.borrowingRegistry = new HashMap<>();
    }

    /**
     * Borrow a book for a member.
     *
     * FAIL FAST (Rule 3): All validations happen at the TOP.
     *   - Member must exist
     *   - Member must not be at their limit
     *   - Book must exist
     *   - An available copy must exist
     * Real work happens ONLY after all checks pass.
     *
     * TELL DON'T ASK (Rule 1):
     *   member.hasReachedBorrowLimit() — not: member.getActiveBorrowings().size() >= 3
     *   copy.markBorrowed() — not: copy.setAvailable(false)
     *   member.addBorrowing(b) — not: member.getActiveBorrowings().add(b)
     */
    public Borrowing borrowBook(String memberId, ISBN isbn) {
        // FAIL FAST — validate existence first
        Member member = library.findMember(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        // Tell member to evaluate its own state (Tell Don't Ask)
        if (member.hasReachedBorrowLimit()) {
            throw new BorrowLimitExceededException(member.getName(), member.getBorrowLimit());
        }

        Book book = library.findBook(isbn)
                .orElseThrow(() -> new BookNotFoundException(isbn.getValue()));

        List<BookCopy> availableCopies = book.getAvailableCopies();
        if (availableCopies.isEmpty()) {
            throw new BookNotAvailableException(book.getTitle());
        }

        // All checks passed — now do the actual work
        BookCopy copy = availableCopies.get(0);
        LocalDate borrowDate = LocalDate.now();
        LocalDate dueDate = borrowDate.plusDays(14);

        Borrowing borrowing = new Borrowing(member, copy, borrowDate, dueDate);

        // TELL DON'T ASK: tell each object to do its own part
        copy.markBorrowed();          // copy manages its own availability state
        member.addBorrowing(borrowing); // member records this in its own list

        borrowingRegistry.put(borrowing.getId(), borrowing);

        System.out.println("[BorrowingService] Borrowing created: " + borrowing.getId()
                + " | " + member.getName() + " borrowed '" + book.getTitle()
                + "' | Due: " + dueDate);

        return borrowing;
    }

    /**
     * Return a book and calculate any fine.
     *
     * FAIL FAST: Borrowing must exist and must not already be returned.
     * INFORMATION EXPERT: Fine is calculated by Borrowing — not by this service.
     * TELL DON'T ASK: borrowing.markReturned() — not: borrowing.setReturnDate(date)
     *
     * @return the fine amount (0.0 if returned on time)
     */
    public double returnBook(String borrowingId) {
        // FAIL FAST — find the borrowing or throw immediately
        Borrowing borrowing = borrowingRegistry.get(borrowingId);
        if (borrowing == null) {
            throw new BorrowingNotFoundException(borrowingId);
        }

        LocalDate returnDate = LocalDate.now();

        // Fine is calculated by Borrowing — it owns dueDate and returnDate
        // BorrowingService does NOT reach into Borrowing's data to do this itself
        borrowing.markReturned(returnDate);  // Borrowing validates + records its own return
        double fine = borrowing.calculateFine(); // Borrowing calculates its own fine

        // Tell the copy and member to update their own state
        borrowing.getCopy().markReturned();
        borrowing.getMember().removeBorrowing(borrowing);

        System.out.println("[BorrowingService] Book returned: '"
                + borrowing.getCopy().getBook().getTitle()
                + "' by " + borrowing.getMember().getName()
                + " | Fine: ₹" + fine);

        return fine;
    }

    /**
     * Return a book with a specific return date.
     * Useful for testing late returns or for administrative corrections.
     */
    public double returnBook(String borrowingId, LocalDate returnDate) {
        Borrowing borrowing = borrowingRegistry.get(borrowingId);
        if (borrowing == null) {
            throw new BorrowingNotFoundException(borrowingId);
        }

        borrowing.markReturned(returnDate);
        double fine = borrowing.calculateFine();

        borrowing.getCopy().markReturned();
        borrowing.getMember().removeBorrowing(borrowing);

        System.out.println("[BorrowingService] Book returned: '"
                + borrowing.getCopy().getBook().getTitle()
                + "' by " + borrowing.getMember().getName()
                + " on " + returnDate
                + " | Fine: ₹" + fine);

        return fine;
    }

    public Optional<Borrowing> findBorrowing(String borrowingId) {
        return Optional.ofNullable(borrowingRegistry.get(borrowingId));
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN — RUNS THE FULL SYSTEM DEMO
// ═══════════════════════════════════════════════════════════════════════════════

public class LibrarySystemDemo {
    public static void main(String[] args) {
        System.out.println("══════════════════════════════════════════════════════");
        System.out.println("  LIBRARY MANAGEMENT SYSTEM — Full Demo");
        System.out.println("  (All LLD concepts combined)");
        System.out.println("══════════════════════════════════════════════════════\n");

        // ── SETUP: Build the library (aggregate root creates everything) ──────
        System.out.println("─── Setting up the library ───\n");

        Library library = new Library("Hyderabad Central Library");

        // ISBN validates itself — invalid formats throw immediately (Fail Fast)
        ISBN cleanCodeIsbn  = new ISBN("9780132350884");
        ISBN ddiIsbn        = new ISBN("9781449373320");
        ISBN pragprogIsbn   = new ISBN("9780135957059");

        // Library creates Books (Composition)
        Book cleanCode  = library.addBook(cleanCodeIsbn,  "Clean Code",             "Robert C. Martin");
        Book ddia       = library.addBook(ddiIsbn,        "Designing Data-Intensive Applications", "Martin Kleppmann");
        Book pragprog   = library.addBook(pragprogIsbn,   "The Pragmatic Programmer", "Andrew Hunt");

        // Book creates its own copies (Composition)
        cleanCode.addCopy("CC-001");
        cleanCode.addCopy("CC-002");
        ddia.addCopy("DDIA-001");
        ddia.addCopy("DDIA-002");
        ddia.addCopy("DDIA-003");
        pragprog.addCopy("PP-001");

        System.out.println();

        // Library creates Members (Composition)
        Member akash  = library.registerMember("M001", "Akash",  "akash@email.com");
        Member priya  = library.registerMember("M002", "Priya",  "priya@email.com");
        Member rahul  = library.registerMember("M003", "Rahul",  "rahul@email.com");

        System.out.println();
        library.printCatalog();

        // ── SCENARIO 1: Normal borrowing flow ────────────────────────────────
        System.out.println("\n─── Scenario 1: Normal borrow and return ───\n");

        BorrowingService service = new BorrowingService(library);

        Borrowing b1 = service.borrowBook("M001", cleanCodeIsbn);
        System.out.println("   " + akash);

        System.out.println();
        Borrowing b2 = service.borrowBook("M002", ddiIsbn);
        System.out.println("   " + priya);

        System.out.println();
        // Return Clean Code on time — no fine
        double fine1 = service.returnBook(b1.getId());
        System.out.println("   Fine for on-time return: ₹" + fine1);
        System.out.println("   " + akash);

        // ── SCENARIO 2: Late return with fine ────────────────────────────────
        System.out.println("\n─── Scenario 2: Late return (fine calculated by Borrowing) ───\n");

        Borrowing b3 = service.borrowBook("M001", ddiIsbn); // borrow again after return

        // Simulate returning 5 days late
        LocalDate lateReturnDate = b3.getDueDate().plusDays(5);
        double fine2 = service.returnBook(b3.getId(), lateReturnDate);
        System.out.println("   Fine for 5-day late return: ₹" + fine2
                + " (₹2 × 5 days = ₹10 expected)");

        // ── SCENARIO 3: Borrow limit enforcement ─────────────────────────────
        System.out.println("\n─── Scenario 3: Borrow limit enforcement (Tell Don't Ask) ───\n");

        // Rahul borrows 3 books (the limit)
        Borrowing rB1 = service.borrowBook("M003", cleanCodeIsbn);
        Borrowing rB2 = service.borrowBook("M003", pragprogIsbn);
        Borrowing rB3 = service.borrowBook("M003", ddiIsbn);
        System.out.println("   " + rahul);

        System.out.println();
        // Try to borrow a 4th — member knows its own limit, rejects it
        System.out.println("[Demo] Attempting to borrow a 4th book:");
        try {
            service.borrowBook("M003", cleanCodeIsbn);
        } catch (BorrowLimitExceededException e) {
            System.out.println("[Caught BorrowLimitExceededException] " + e.getMessage());
        }

        // ── SCENARIO 4: Book unavailability ───────────────────────────────────
        System.out.println("\n─── Scenario 4: No available copies ───\n");

        // Pragmatic Programmer has only 1 copy and Rahul has it
        System.out.println("[Demo] Akash tries to borrow Pragmatic Programmer (only 1 copy, Rahul has it):");
        try {
            service.borrowBook("M001", pragprogIsbn);
        } catch (BookNotAvailableException e) {
            System.out.println("[Caught BookNotAvailableException] " + e.getMessage());
        }

        // ── SCENARIO 5: Invalid inputs (Fail Fast) ────────────────────────────
        System.out.println("\n─── Scenario 5: Fail fast — invalid inputs caught immediately ───\n");

        System.out.println("[Demo] Invalid ISBN format:");
        try {
            new ISBN("not-a-isbn"); // fails immediately in constructor
        } catch (IllegalArgumentException e) {
            System.out.println("[Caught IllegalArgumentException] " + e.getMessage());
        }

        System.out.println();
        System.out.println("[Demo] Member not found:");
        try {
            service.borrowBook("M999", cleanCodeIsbn);
        } catch (MemberNotFoundException e) {
            System.out.println("[Caught MemberNotFoundException] " + e.getMessage());
        }

        System.out.println();
        System.out.println("[Demo] Book not found:");
        try {
            service.borrowBook("M001", new ISBN("9999999999999"));
        } catch (BookNotFoundException e) {
            System.out.println("[Caught BookNotFoundException] " + e.getMessage());
        }

        System.out.println();
        System.out.println("[Demo] Returning an already-returned book:");
        try {
            service.returnBook(b1.getId()); // b1 was already returned in Scenario 1
        } catch (BorrowingNotFoundException e) {
            // Actually b1 was returned, so it may not be in registry
            System.out.println("[Expected error]");
        } catch (AlreadyReturnedException e) {
            System.out.println("[Caught AlreadyReturnedException] " + e.getMessage());
        }

        // ── FINAL STATE ────────────────────────────────────────────────────────
        System.out.println("\n─── Final state of the library ───");
        library.printCatalog();

        System.out.println("\n─── Final member states ───");
        for (Member m : library.getAllMembers()) {
            System.out.println("   " + m
                    + (m.hasOverdueBooks() ? " ⚠ HAS OVERDUE BOOKS" : ""));
        }

        // ── SUMMARY ────────────────────────────────────────────────────────────
        System.out.println("\n══════════════════════════════════════════════════════");
        System.out.println("  DESIGN DECISIONS SUMMARY");
        System.out.println("══════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  ISBN             → Value Object: immutable, value-equality, validates itself");
        System.out.println("  Library → Book   → Composition: Library creates Books, Books die with Library");
        System.out.println("  Book → BookCopy  → Composition: Book creates Copies, package-private constructor");
        System.out.println("  Member→Borrowing → Composition: Borrowings exist because of Member's activity");
        System.out.println("  Borrowing→Member → Association: Borrowing knows its Member, doesn't own it");
        System.out.println("  Borrowing→Copy   → Association: Borrowing knows its Copy, doesn't own it");
        System.out.println("  Service→Library  → Association: Service uses Library, injected, not created");
        System.out.println();
        System.out.println("  calculateFine()  → On Borrowing: owns dueDate + returnDate (Information Expert)");
        System.out.println("  hasReachedLimit()→ On Member:    member knows its own limit (Tell Don't Ask)");
        System.out.println("  markBorrowed()   → On BookCopy:  copy validates + manages own state (TDA)");
        System.out.println("  findBook()       → Optional<Book>: makes 'not found' explicit (Rich Returns)");
        System.out.println("  All boundaries   → Fail Fast: validate at entry, named domain exceptions");
    }
}
