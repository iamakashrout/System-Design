import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Library Management System — Classic LLD Interview Problem #9.
 *
 * Core complexity this system captures:
 *   - A copy-level state machine (AVAILABLE, BORROWED, RESERVED, LOST) that
 *     must be transitioned safely under concurrent borrow attempts.
 *   - Concurrent claiming of a scarce resource: when N members race for the
 *     last available copy of a popular title, exactly one should win — the
 *     same lock-free claim idiom used for driver matching in the Ride
 *     Sharing System (Problem 8), reapplied here at the BookCopy level.
 *   - A reservation queue that hands a returned copy to the next waiting
 *     member instead of releasing it back to general availability.
 *   - Pluggable overdue-fine calculation.
 *   - Search by title, author, and ISBN via maintained indices.
 *
 * Patterns used:
 *   - State     -> CopyStatus (rich enum with an explicit transition table)
 *   - Strategy  -> FineCalculationStrategy
 *   - Observer  -> ReservationObserver and its implementations
 *   - Facade    -> LibraryService, the single entry point for clients
 *
 * Concurrency model:
 *   - BookCopy.status is an AtomicReference<CopyStatus>. Claiming a copy is
 *     read-current -> check legality against the state table -> CAS. If the
 *     CAS fails, another thread claimed that specific copy in the gap; the
 *     caller drops it and tries the next candidate copy. No lock is ever
 *     held over a Book's copy list while claiming.
 *   - A Book's reservation queue is a ConcurrentLinkedQueue<String> (member
 *     ids). queue.poll() is itself atomic, so when two copies of the same
 *     book are returned concurrently and only one reservation is pending,
 *     exactly one return event wins the hand-off — no extra lock needed.
 *   - Member.outstandingFines uses plain `synchronized` methods rather than
 *     an Atomic type: fine updates are low-frequency (one per return/loss),
 *     so a monitor lock is the right-sized tool — unlike the high-frequency
 *     revenue counter in the Ride Sharing System, which justified DoubleAdder.
 *   - Catalog's title/author indices are ConcurrentHashMap<String,
 *     CopyOnWriteArrayList<Book>> — reads (searches) vastly outnumber writes
 *     (adding a book), which is exactly the access pattern CopyOnWriteArrayList
 *     is built for.
 */
public class LibraryManagementSystem {

    // =====================================================================
    // Exceptions
    // =====================================================================

    public static class InvalidCopyStateException extends RuntimeException {
        public InvalidCopyStateException(String message) { super(message); }
    }

    public static class EntityNotFoundException extends RuntimeException {
        public EntityNotFoundException(String message) { super(message); }
    }

    public static class NoCopyAvailableException extends RuntimeException {
        public NoCopyAvailableException(String message) { super(message); }
    }

    public static class IllegalReservationException extends RuntimeException {
        public IllegalReservationException(String message) { super(message); }
    }

    // =====================================================================
    // State — CopyStatus
    // =====================================================================

    /**
     * Rich enum modeling the lifecycle of a single physical BookCopy.
     *
     *   AVAILABLE -> BORROWED, LOST
     *   BORROWED  -> AVAILABLE, RESERVED, LOST
     *   RESERVED  -> BORROWED, AVAILABLE, RESERVED
     *   LOST      -> AVAILABLE
     *
     * RESERVED -> RESERVED is legal: when a held copy's pickup window
     * expires, the system advances the hold to the next member in the
     * queue without ever exposing the copy as generally AVAILABLE.
     * LOST -> AVAILABLE is the librarian's explicit "found it" override.
     */
    public enum CopyStatus {
        AVAILABLE {
            @Override public boolean canTransitionTo(CopyStatus target) {
                return target == BORROWED || target == LOST;
            }
        },
        BORROWED {
            @Override public boolean canTransitionTo(CopyStatus target) {
                return target == AVAILABLE || target == RESERVED || target == LOST;
            }
        },
        RESERVED {
            @Override public boolean canTransitionTo(CopyStatus target) {
                return target == BORROWED || target == AVAILABLE || target == RESERVED;
            }
        },
        LOST {
            @Override public boolean canTransitionTo(CopyStatus target) {
                return target == AVAILABLE;
            }
        };

        public abstract boolean canTransitionTo(CopyStatus target);
    }

    // =====================================================================
    // BookCopy — the unit of concurrent contention
    // =====================================================================

    public static class BookCopy {
        private final String copyId;
        private final String isbn;
        private final AtomicReference<CopyStatus> status;
        private volatile String reservedForMemberId;

        public BookCopy(String copyId, String isbn) {
            this.copyId = copyId;
            this.isbn = isbn;
            this.status = new AtomicReference<>(CopyStatus.AVAILABLE);
        }

        /**
         * Attempts to move this copy to {@code target}. Reads the current
         * state, validates the transition against the state table, then
         * CASes. A {@code false} return means another thread changed the
         * status in the gap between the read and the CAS — the caller's
         * job is to decide whether that's "lost the race, try the next
         * candidate" (claiming) or a genuine error (everywhere else).
         */
        public boolean tryTransition(CopyStatus target) {
            CopyStatus current = status.get();
            if (!current.canTransitionTo(target)) {
                throw new InvalidCopyStateException(
                        "Copy " + copyId + " cannot move from " + current + " to " + target);
            }
            return status.compareAndSet(current, target);
        }

        public String getCopyId() { return copyId; }
        public String getIsbn() { return isbn; }
        public CopyStatus getStatus() { return status.get(); }
        public String getReservedForMemberId() { return reservedForMemberId; }
        public void setReservedForMemberId(String memberId) { this.reservedForMemberId = memberId; }

        @Override public String toString() {
            return copyId + "[" + status.get() + "]";
        }
    }

    // =====================================================================
    // Book — catalog entry, owns its copies and reservation queue
    // =====================================================================

    public static class Book {
        private final String isbn;
        private final String title;
        private final String author;
        private final List<BookCopy> copies = Collections.synchronizedList(new ArrayList<>());
        private final Queue<String> reservationQueue = new ConcurrentLinkedQueue<>();

        public Book(String isbn, String title, String author) {
            this.isbn = isbn;
            this.title = title;
            this.author = author;
        }

        public void addCopy(BookCopy copy) { copies.add(copy); }

        /** Defensive snapshot — safe to iterate outside the list's own lock. */
        public List<BookCopy> getCopies() {
            synchronized (copies) {
                return new ArrayList<>(copies);
            }
        }

        public boolean hasAvailableCopy() {
            synchronized (copies) {
                for (BookCopy c : copies) {
                    if (c.getStatus() == CopyStatus.AVAILABLE) return true;
                }
                return false;
            }
        }

        public void enqueueReservation(String memberId) { reservationQueue.offer(memberId); }

        /** Atomically pops the next waiting member, or null if none. */
        public String pollReservationQueue() { return reservationQueue.poll(); }

        public String getIsbn() { return isbn; }
        public String getTitle() { return title; }
        public String getAuthor() { return author; }
    }

    // =====================================================================
    // Users — LibraryUser hierarchy
    // =====================================================================

    public abstract static class LibraryUser {
        protected final String id;
        protected final String name;

        protected LibraryUser(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
    }

    public static class Member extends LibraryUser {
        private final List<Loan> activeLoans = Collections.synchronizedList(new ArrayList<>());
        private double outstandingFines = 0.0;

        public Member(String id, String name) { super(id, name); }

        public void addActiveLoan(Loan loan) { activeLoans.add(loan); }
        public void removeActiveLoan(Loan loan) { activeLoans.remove(loan); }

        public List<Loan> getActiveLoans() {
            synchronized (activeLoans) {
                return new ArrayList<>(activeLoans);
            }
        }

        public synchronized void addFine(double amount) { outstandingFines += amount; }

        /** Pays up to {@code amount} towards the balance; returns what was actually applied. */
        public synchronized double payFine(double amount) {
            double payment = Math.min(amount, outstandingFines);
            outstandingFines -= payment;
            return payment;
        }

        public synchronized double getOutstandingFines() { return outstandingFines; }
    }

    public static class Librarian extends LibraryUser {
        public Librarian(String id, String name) { super(id, name); }
    }

    // =====================================================================
    // Loan
    // =====================================================================

    public static class Loan {
        private final String loanId;
        private final BookCopy copy;
        private final Member member;
        private final LocalDate borrowDate;
        private final LocalDate dueDate;
        private volatile LocalDate returnDate;
        private volatile double fineAmount;

        public Loan(String loanId, BookCopy copy, Member member, LocalDate borrowDate, LocalDate dueDate) {
            this.loanId = loanId;
            this.copy = copy;
            this.member = member;
            this.borrowDate = borrowDate;
            this.dueDate = dueDate;
        }

        public long daysOverdue(LocalDate asOf) {
            long days = ChronoUnit.DAYS.between(dueDate, asOf);
            return Math.max(0, days);
        }

        public void markReturned(LocalDate returnDate, double fineAmount) {
            this.returnDate = returnDate;
            this.fineAmount = fineAmount;
        }

        public String getLoanId() { return loanId; }
        public BookCopy getCopy() { return copy; }
        public Member getMember() { return member; }
        public LocalDate getBorrowDate() { return borrowDate; }
        public LocalDate getDueDate() { return dueDate; }
        public LocalDate getReturnDate() { return returnDate; }
        public double getFineAmount() { return fineAmount; }

        @Override public String toString() {
            return "Loan{" + loanId + ", copy=" + copy.getCopyId() + ", due=" + dueDate
                    + ", returned=" + returnDate + ", fine=" + String.format("%.2f", fineAmount) + "}";
        }
    }

    // =====================================================================
    // Strategy — fine calculation
    // =====================================================================

    public interface FineCalculationStrategy {
        double calculateFine(long daysOverdue);
    }

    public static class FlatRateFineStrategy implements FineCalculationStrategy {
        private final double ratePerDay;

        public FlatRateFineStrategy(double ratePerDay) { this.ratePerDay = ratePerDay; }

        @Override public double calculateFine(long daysOverdue) {
            return daysOverdue <= 0 ? 0.0 : daysOverdue * ratePerDay;
        }
    }

    /** First {@code weekThreshold} overdue days at {@code earlyRate}, every day after at {@code escalatedRate}. */
    public static class TieredFineStrategy implements FineCalculationStrategy {
        private final long weekThreshold;
        private final double earlyRate;
        private final double escalatedRate;

        public TieredFineStrategy(long weekThreshold, double earlyRate, double escalatedRate) {
            this.weekThreshold = weekThreshold;
            this.earlyRate = earlyRate;
            this.escalatedRate = escalatedRate;
        }

        @Override public double calculateFine(long daysOverdue) {
            if (daysOverdue <= 0) return 0.0;
            long earlyDays = Math.min(daysOverdue, weekThreshold);
            long escalatedDays = Math.max(0, daysOverdue - weekThreshold);
            return earlyDays * earlyRate + escalatedDays * escalatedRate;
        }
    }

    // =====================================================================
    // Observer — reservation notifications
    // =====================================================================

    public interface ReservationObserver {
        void onReservationCreated(Member member, Book book);
        void onBookAvailableForPickup(Member member, Book book, BookCopy copy);
        void onReservationExpired(Member member, Book book);
    }

    public static class MemberNotificationObserver implements ReservationObserver {
        @Override public void onReservationCreated(Member member, Book book) {
            System.out.println("  [member alert] " + member.getName() + ": queued for \"" + book.getTitle() + "\"");
        }

        @Override public void onBookAvailableForPickup(Member member, Book book, BookCopy copy) {
            System.out.println("  [member alert] " + member.getName() + ": \"" + book.getTitle()
                    + "\" (" + copy.getCopyId() + ") is ready for pickup");
        }

        @Override public void onReservationExpired(Member member, Book book) {
            System.out.println("  [member alert] " + member.getName() + ": hold on \"" + book.getTitle() + "\" expired");
        }
    }

    /**
     * Logs reservation lifecycle events through the Observer interface, plus
     * a couple of direct, non-reservation audit hooks (lost copies, overdue
     * returns) called straight from LibraryService. Those aren't reservation
     * notifications, so they deliberately sit outside ReservationObserver's
     * contract rather than stretching the interface to cover everything an
     * audit log might want.
     */
    public static class LibrarianAuditObserver implements ReservationObserver {
        private final List<String> auditLog = Collections.synchronizedList(new ArrayList<>());

        @Override public void onReservationCreated(Member member, Book book) {
            log("RESERVATION_CREATED member=" + member.getId() + " isbn=" + book.getIsbn());
        }

        @Override public void onBookAvailableForPickup(Member member, Book book, BookCopy copy) {
            log("READY_FOR_PICKUP member=" + member.getId() + " copy=" + copy.getCopyId());
        }

        @Override public void onReservationExpired(Member member, Book book) {
            log("RESERVATION_EXPIRED member=" + member.getId() + " isbn=" + book.getIsbn());
        }

        public void logCopyLost(BookCopy copy) {
            log("COPY_LOST copy=" + copy.getCopyId() + " isbn=" + copy.getIsbn());
        }

        public void logOverdueReturn(Loan loan, double fine) {
            log("OVERDUE_RETURN loan=" + loan.getLoanId() + " fine=" + String.format("%.2f", fine));
        }

        private void log(String entry) { auditLog.add(entry); }

        public void printLog() {
            synchronized (auditLog) {
                for (String entry : auditLog) System.out.println("  [audit] " + entry);
            }
        }
    }

    // =====================================================================
    // Catalog — search indices
    // =====================================================================

    public static class Catalog {
        private final ConcurrentMap<String, Book> isbnIndex = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, CopyOnWriteArrayList<Book>> titleIndex = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, CopyOnWriteArrayList<Book>> authorIndex = new ConcurrentHashMap<>();

        public void addBook(Book book) {
            isbnIndex.put(book.getIsbn(), book);
            titleIndex.computeIfAbsent(normalize(book.getTitle()), k -> new CopyOnWriteArrayList<>()).add(book);
            authorIndex.computeIfAbsent(normalize(book.getAuthor()), k -> new CopyOnWriteArrayList<>()).add(book);
        }

        public Optional<Book> findByIsbn(String isbn) {
            return Optional.ofNullable(isbnIndex.get(isbn));
        }

        public List<Book> findByTitle(String title) {
            return new ArrayList<>(titleIndex.getOrDefault(normalize(title), new CopyOnWriteArrayList<>()));
        }

        public List<Book> findByAuthor(String author) {
            return new ArrayList<>(authorIndex.getOrDefault(normalize(author), new CopyOnWriteArrayList<>()));
        }

        private static String normalize(String s) {
            return s == null ? "" : s.trim().toLowerCase();
        }
    }

    // =====================================================================
    // Facade — LibraryService
    // =====================================================================

    public static class LibraryService {
        private static final int LOAN_PERIOD_DAYS = 14;
        private static final double REPLACEMENT_FEE = 25.0;

        private final Catalog catalog = new Catalog();
        private final ConcurrentMap<String, Member> members = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Librarian> librarians = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, BookCopy> copyIndex = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Loan> activeLoanByCopyId = new ConcurrentHashMap<>();
        private final List<ReservationObserver> observers = new CopyOnWriteArrayList<>();
        private final FineCalculationStrategy fineStrategy;
        private final AtomicInteger loanIdSeq = new AtomicInteger(1);
        private final AtomicInteger copyIdSeq = new AtomicInteger(1);

        public LibraryService(FineCalculationStrategy fineStrategy) {
            this.fineStrategy = fineStrategy;
        }

        public void registerObserver(ReservationObserver observer) { observers.add(observer); }

        // --- registration --------------------------------------------------

        public Member registerMember(String memberId, String name) {
            Member member = new Member(memberId, name);
            members.put(memberId, member);
            return member;
        }

        public Librarian registerLibrarian(String librarianId, String name) {
            Librarian librarian = new Librarian(librarianId, name);
            librarians.put(librarianId, librarian);
            return librarian;
        }

        // --- librarian actions ----------------------------------------------

        public Book addBook(Librarian librarian, String isbn, String title, String author) {
            Book book = new Book(isbn, title, author);
            catalog.addBook(book);
            return book;
        }

        public BookCopy addCopy(Librarian librarian, String isbn) {
            Book book = getBookOrThrow(isbn);
            BookCopy copy = new BookCopy("C" + copyIdSeq.getAndIncrement(), isbn);
            book.addCopy(copy);
            copyIndex.put(copy.getCopyId(), copy);
            return copy;
        }

        public void markCopyLost(Librarian librarian, String copyId) {
            BookCopy copy = findCopyOrThrow(copyId);
            CopyStatus previous = copy.getStatus();
            if (!copy.tryTransition(CopyStatus.LOST)) {
                throw new InvalidCopyStateException("Concurrent modification on copy " + copyId + ", retry");
            }
            if (previous == CopyStatus.BORROWED) {
                Loan loan = activeLoanByCopyId.remove(copyId);
                if (loan != null) {
                    loan.getMember().removeActiveLoan(loan);
                    loan.markReturned(LocalDate.now(), 0.0);
                    loan.getMember().addFine(REPLACEMENT_FEE);
                }
            }
            auditDirect(a -> a.logCopyLost(copy));
        }

        public void markCopyFound(Librarian librarian, String copyId) {
            BookCopy copy = findCopyOrThrow(copyId);
            if (!copy.tryTransition(CopyStatus.AVAILABLE)) {
                throw new InvalidCopyStateException("Copy " + copyId + " is not currently LOST");
            }
        }

        // --- borrow / return / reserve ---------------------------------------

        /**
         * Claims a copy for the member: first preference is a copy already
         * held in RESERVED for them, otherwise any AVAILABLE copy via the
         * same lock-free CAS-claim loop used for driver matching in the
         * Ride Sharing System — try a candidate, and if the CAS loses the
         * race, move to the next one instead of blocking or failing.
         */
        public Loan borrowBook(String memberId, String isbn) {
            Member member = getMemberOrThrow(memberId);
            Book book = getBookOrThrow(isbn);

            for (BookCopy copy : book.getCopies()) {
                if (copy.getStatus() == CopyStatus.RESERVED && memberId.equals(copy.getReservedForMemberId())) {
                    if (copy.tryTransition(CopyStatus.BORROWED)) {
                        return createLoan(copy, member);
                    }
                }
            }
            for (BookCopy copy : book.getCopies()) {
                if (copy.getStatus() == CopyStatus.AVAILABLE && copy.tryTransition(CopyStatus.BORROWED)) {
                    return createLoan(copy, member);
                }
                // CAS lost (or status had already moved on) -> try the next candidate copy
            }
            throw new NoCopyAvailableException(
                    "No copy of \"" + book.getTitle() + "\" available — consider reserveBook()");
        }

        private Loan createLoan(BookCopy copy, Member member) {
            LocalDate today = LocalDate.now();
            Loan loan = new Loan("L" + loanIdSeq.getAndIncrement(), copy, member, today, today.plusDays(LOAN_PERIOD_DAYS));
            activeLoanByCopyId.put(copy.getCopyId(), loan);
            member.addActiveLoan(loan);
            return loan;
        }

        public Loan returnBook(String copyId) {
            return returnBook(copyId, LocalDate.now());
        }

        /** Overload accepting an explicit return date — used here to deterministically demo overdue fines. */
        public Loan returnBook(String copyId, LocalDate returnDate) {
            Loan loan = activeLoanByCopyId.remove(copyId);
            if (loan == null) {
                throw new EntityNotFoundException("No active loan for copy " + copyId);
            }
            BookCopy copy = loan.getCopy();
            Book book = getBookOrThrow(copy.getIsbn());
            Member member = loan.getMember();

            long overdueDays = loan.daysOverdue(returnDate);
            double fine = overdueDays > 0 ? fineStrategy.calculateFine(overdueDays) : 0.0;
            loan.markReturned(returnDate, fine);
            member.removeActiveLoan(loan);
            if (fine > 0) {
                member.addFine(fine);
                auditDirect(a -> a.logOverdueReturn(loan, fine));
            }

            String nextMemberId = book.pollReservationQueue();
            CopyStatus target = (nextMemberId != null) ? CopyStatus.RESERVED : CopyStatus.AVAILABLE;
            if (!copy.tryTransition(target)) {
                throw new InvalidCopyStateException("Unexpected concurrent state change on copy " + copyId);
            }
            if (nextMemberId != null) {
                copy.setReservedForMemberId(nextMemberId);
                Member waitingMember = members.get(nextMemberId);
                if (waitingMember != null) {
                    notifyAvailableForPickup(waitingMember, book, copy);
                }
            } else {
                copy.setReservedForMemberId(null);
            }
            return loan;
        }

        public void reserveBook(String memberId, String isbn) {
            Member member = getMemberOrThrow(memberId);
            Book book = getBookOrThrow(isbn);
            if (book.hasAvailableCopy()) {
                throw new IllegalReservationException(
                        "Copies of \"" + book.getTitle() + "\" are available — borrow directly instead of reserving");
            }
            book.enqueueReservation(memberId);
            notifyReservationCreated(member, book);
        }

        // --- search -----------------------------------------------------------

        public Optional<Book> searchByIsbn(String isbn) { return catalog.findByIsbn(isbn); }
        public List<Book> searchByTitle(String title) { return catalog.findByTitle(title); }
        public List<Book> searchByAuthor(String author) { return catalog.findByAuthor(author); }

        // --- internals ----------------------------------------------------------

        private void notifyReservationCreated(Member member, Book book) {
            for (ReservationObserver obs : observers) obs.onReservationCreated(member, book);
        }

        private void notifyAvailableForPickup(Member member, Book book, BookCopy copy) {
            for (ReservationObserver obs : observers) obs.onBookAvailableForPickup(member, book, copy);
        }

        private void auditDirect(Consumer<LibrarianAuditObserver> action) {
            for (ReservationObserver obs : observers) {
                if (obs instanceof LibrarianAuditObserver) {
                    action.accept((LibrarianAuditObserver) obs);
                }
            }
        }

        private Book getBookOrThrow(String isbn) {
            return catalog.findByIsbn(isbn)
                    .orElseThrow(() -> new EntityNotFoundException("No book with isbn " + isbn));
        }

        private Member getMemberOrThrow(String memberId) {
            Member member = members.get(memberId);
            if (member == null) throw new EntityNotFoundException("No member with id " + memberId);
            return member;
        }

        private BookCopy findCopyOrThrow(String copyId) {
            BookCopy copy = copyIndex.get(copyId);
            if (copy == null) throw new EntityNotFoundException("No copy with id " + copyId);
            return copy;
        }
    }

    // =====================================================================
    // Demo
    // =====================================================================

    public static void main(String[] args) throws Exception {
        FineCalculationStrategy tieredFine = new TieredFineStrategy(7, 0.25, 1.00);
        LibraryService service = new LibraryService(tieredFine);

        LibrarianAuditObserver auditObserver = new LibrarianAuditObserver();
        service.registerObserver(new MemberNotificationObserver());
        service.registerObserver(auditObserver);

        Librarian librarian = service.registerLibrarian("LIB1", "Mrs. Patel");
        Member alice = service.registerMember("M1", "Alice");
        Member bob = service.registerMember("M2", "Bob");
        Member carol = service.registerMember("M3", "Carol");

        service.addBook(librarian, "ISBN-001", "Designing Data-Intensive Applications", "Martin Kleppmann");
        service.addCopy(librarian, "ISBN-001");
        service.addCopy(librarian, "ISBN-001");

        service.addBook(librarian, "ISBN-002", "Effective Java", "Joshua Bloch");
        BookCopy ejCopy = service.addCopy(librarian, "ISBN-002");

        System.out.println("--- Scenario 1: search and a straightforward on-time return ---");
        System.out.println("  Search by author 'Joshua Bloch': "
                + service.searchByAuthor("Joshua Bloch").size() + " result(s)");
        System.out.println("  Search by isbn 'ISBN-001': "
                + service.searchByIsbn("ISBN-001").map(Book::getTitle).orElse("not found"));

        Loan aliceLoan = service.borrowBook("M1", "ISBN-002");
        System.out.println("  Alice borrowed " + aliceLoan.getCopy().getCopyId() + ", due " + aliceLoan.getDueDate());
        Loan returned = service.returnBook(ejCopy.getCopyId(), aliceLoan.getDueDate().minusDays(2));
        System.out.println("  Returned early, fine = " + returned.getFineAmount());

        System.out.println();
        System.out.println("--- Scenario 2: concurrent CAS race for the last copy ---");
        service.borrowBook("M1", "ISBN-001"); // claims one of the two copies, leaving exactly one AVAILABLE
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (String memberId : List.of("M2", "M3")) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    Loan loan = service.borrowBook(memberId, "ISBN-001");
                    return memberId + " WON copy " + loan.getCopy().getCopyId();
                } catch (NoCopyAvailableException e) {
                    return memberId + " LOST the race: " + e.getMessage();
                }
            }));
        }
        ready.await();
        go.countDown();
        for (Future<String> f : results) {
            System.out.println("  " + f.get());
        }
        pool.shutdown();

        System.out.println();
        System.out.println("--- Scenario 3: overdue return with tiered fine ---");
        Loan carolLoan = service.borrowBook("M3", "ISBN-002");
        LocalDate lateReturn = carolLoan.getDueDate().plusDays(10); // 7 days at 0.25 + 3 days at 1.00
        Loan lateLoan = service.returnBook(ejCopy.getCopyId(), lateReturn);
        System.out.println("  Returned 10 days late, fine = " + String.format("%.2f", lateLoan.getFineAmount())
                + " (carol owes " + String.format("%.2f", carol.getOutstandingFines()) + ")");

        System.out.println();
        System.out.println("--- Scenario 4: reservation hand-off ---");
        // At this point ISBN-002 has its single copy back with Carol's loan closed, so it's AVAILABLE again.
        try {
            service.reserveBook("M1", "ISBN-002");
            System.out.println("  ERROR: should not reach here, a copy is available");
        } catch (IllegalReservationException e) {
            System.out.println("  Correctly rejected reservation: " + e.getMessage());
        }
        Loan bobLoan = service.borrowBook("M2", "ISBN-002"); // takes the only copy
        try {
            service.borrowBook("M1", "ISBN-002");
            System.out.println("  ERROR: should not reach here");
        } catch (NoCopyAvailableException e) {
            System.out.println("  Alice could not borrow: " + e.getMessage());
        }
        service.reserveBook("M1", "ISBN-002");
        service.returnBook(bobLoan.getCopy().getCopyId());
        Loan aliceClaim = service.borrowBook("M1", "ISBN-002");
        System.out.println("  Alice claimed her held copy: " + aliceClaim.getCopy());

        System.out.println();
        System.out.println("--- Scenario 5: lost copy and recovery ---");
        service.addCopy(librarian, "ISBN-001"); // fresh copy so this scenario is deterministic regardless of who won scenario 2's race
        Loan bobLoan2 = service.borrowBook("M2", "ISBN-001");
        String lostCopyId = bobLoan2.getCopy().getCopyId();
        service.markCopyLost(librarian, lostCopyId);
        System.out.println("  Copy " + lostCopyId + " marked LOST, Bob owes "
                + String.format("%.2f", bob.getOutstandingFines()) + " (replacement fee)");
        try {
            service.returnBook(lostCopyId);
            System.out.println("  ERROR: should not reach here");
        } catch (EntityNotFoundException e) {
            System.out.println("  Correctly rejected return of lost copy: " + e.getMessage());
        }
        service.markCopyFound(librarian, lostCopyId);
        System.out.println("  Copy " + lostCopyId + " found and returned to circulation: "
                + service.searchByIsbn("ISBN-001").get().getCopies());

        System.out.println();
        auditObserver.printLog();
    }
}
