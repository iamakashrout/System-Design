/**
 * RELATIONSHIP 1: DEPENDENCY — "I borrow it, I don't keep it"
 *
 * Definition:
 *   Class A uses Class B only INSIDE a method — as a parameter or local variable.
 *   After the method returns, A has no memory of B whatsoever.
 *   No field reference is stored.
 *
 * Real-world analogy:
 *   You borrow a pen from someone to sign a document.
 *   Once you're done, you hand it back. You don't carry the pen with you.
 *
 * Code signal:
 *   The external class appears ONLY as a method parameter or local variable.
 *   It is NEVER stored as a field.
 *
 * UML notation: A - - -> B  (dashed arrow)
 */

// ─── Supporting Classes ──────────────────────────────────────────────────────

/**
 * PdfExporter is the class being "depended upon".
 * It has no knowledge of Invoice — the dependency is one-way.
 */
class PdfExporter {
    public void export(String content) {
        System.out.println("[PdfExporter] Exporting to PDF: " + content);
    }
}

/**
 * HtmlExporter is an alternative exporter.
 * Swapping PdfExporter with HtmlExporter requires zero changes to Invoice.
 * This is the key benefit of dependency (vs. storing it as a field).
 */
class HtmlExporter {
    public void export(String content) {
        System.out.println("[HtmlExporter] Exporting to HTML: <p>" + content + "</p>");
    }
}

/**
 * ReportGenerator uses a PrinterService to do its work, but doesn't store it.
 * The printer is only needed for the duration of the generate() call.
 */
class PrinterService {
    public void print(String content) {
        System.out.println("[PrinterService] Printing: " + content);
    }
}

// ─── Main Class Demonstrating Dependency ─────────────────────────────────────

/**
 * Invoice DEPENDS ON PdfExporter — but only temporarily.
 *
 * Notice:
 * - There is NO field of type PdfExporter in this class.
 * - The exporter is passed in as a method parameter.
 * - Invoice doesn't know or care which exporter it gets — just that it can export.
 */
class Invoice {
    private final String invoiceId;
    private final String content;

    public Invoice(String invoiceId, String content) {
        this.invoiceId = invoiceId;
        this.content = content;
    }

    /**
     * DEPENDENCY in action:
     * PdfExporter is used here and ONLY here.
     * Once this method returns, Invoice has no reference to the exporter.
     * Tomorrow you can pass HtmlExporter instead — Invoice doesn't need to change.
     */
    public void exportAsPdf(PdfExporter exporter) {
        System.out.println("[Invoice] Exporting invoice " + invoiceId);
        exporter.export(content); // uses it temporarily
        // After this method returns — no trace of PdfExporter remains in Invoice
    }

    public String getContent() { return content; }
}

/**
 * ReportGenerator depends on PrinterService to do its work.
 * Again — no field stored, printer lives only in the method scope.
 */
class ReportGenerator {
    private final String reportTitle;

    public ReportGenerator(String reportTitle) {
        this.reportTitle = reportTitle;
    }

    /**
     * DEPENDENCY via method parameter.
     * The PrinterService is borrowed for this one call, then "forgotten".
     */
    public void generate(PrinterService printer) {
        String content = buildContent();
        printer.print(content);
    }

    private String buildContent() {
        return "Report: " + reportTitle + " | Generated at: " + java.time.LocalDate.now();
    }
}

// ─── Runner ───────────────────────────────────────────────────────────────────

public class DependencyDemo {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  DEPENDENCY RELATIONSHIP DEMO");
        System.out.println("========================================\n");

        // Invoice uses PdfExporter — passed in, not stored
        Invoice invoice = new Invoice("INV-001", "Order #42 — ₹5000");
        PdfExporter pdfExporter = new PdfExporter();
        invoice.exportAsPdf(pdfExporter);

        System.out.println();

        // Swap to a different exporter — Invoice is unaffected, needs zero change
        // This is the power of dependency: the caller decides which tool to use
        System.out.println("[Demo] Swapping exporter — Invoice needs no changes:");
        HtmlExporter htmlExporter = new HtmlExporter();
        htmlExporter.export(invoice.getContent()); // works the same way

        System.out.println();

        // ReportGenerator depends on PrinterService temporarily
        ReportGenerator report = new ReportGenerator("Q3 Sales Summary");
        PrinterService printer = new PrinterService();
        report.generate(printer);

        System.out.println("\n--- Key Takeaway ---");
        System.out.println("Dependency = method-level relationship only.");
        System.out.println("No field stored. Object 'borrows' the dependency for one call.");
        System.out.println("The caller decides which implementation to inject.");
    }
}
