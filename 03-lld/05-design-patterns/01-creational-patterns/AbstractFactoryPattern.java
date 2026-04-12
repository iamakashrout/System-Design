// =============================================================================
// PATTERN: Abstract Factory
// PURPOSE: Provide an interface for creating FAMILIES of related objects
//          without specifying their concrete classes.
//
// REAL-WORLD ANALOGY:
//   Think of a furniture store with collections. The "Modern" collection has
//   a Modern sofa, Modern table, and Modern chair — all consistent with each
//   other. The "Victorian" collection has its own matched set. You pick a
//   collection (factory), and everything you get from it is guaranteed to
//   be compatible. You'd never mix a Modern sofa with a Victorian chair.
//
// FACTORY METHOD vs ABSTRACT FACTORY:
//   Factory Method  → creates ONE product. "Give me a vehicle."
//   Abstract Factory → creates a FAMILY of products. "Give me a full Mac UI
//                      (button + textfield + dialog) — all consistent."
//
// THE PROBLEM IT SOLVES:
//   Without this pattern, you might create products independently:
//     Button b = new MacButton();
//     Dialog d = new WindowsDialog(); // ← MISMATCH! Mac button + Windows dialog
//   Abstract Factory makes this kind of mismatch structurally impossible.
//   The factory guarantees you get a consistent family.
//
// FOUR INGREDIENTS:
//   1. Abstract product interfaces  → Button, TextField, Dialog
//   2. Concrete products            → MacButton, WindowsButton, etc.
//   3. Abstract factory interface   → UIFactory (createButton, createDialog, etc.)
//   4. Concrete factories           → MacUIFactory, WindowsUIFactory
// =============================================================================

public class AbstractFactoryPattern {

    // =========================================================================
    // STEP 1: ABSTRACT PRODUCT INTERFACES
    // One interface per product type. All families must implement these.
    // =========================================================================

    // Every button — regardless of OS — must render and handle clicks.
    interface Button {
        void render();
        void onClick();
    }

    // Every text field — regardless of OS — must render and return its value.
    interface TextField {
        void render();
        String getValue();
        void setValue(String value);
    }

    // Every dialog — regardless of OS — must render.
    interface Dialog {
        void render();
        void close();
    }


    // =========================================================================
    // STEP 2: CONCRETE PRODUCTS — Windows Family
    // All Windows products are consistent with each other.
    // =========================================================================

    static class WindowsButton implements Button {
        @Override
        public void render() {
            System.out.println("    [Windows Button] Rendering flat, square button with shadow");
        }
        @Override
        public void onClick() {
            System.out.println("    [Windows Button] Click → ripple animation → action triggered");
        }
    }

    static class WindowsTextField implements TextField {
        private String value = "";

        @Override
        public void render() {
            System.out.println("    [Windows TextField] Rendering underline-style input field");
        }
        @Override
        public String getValue() { return value; }
        @Override
        public void setValue(String value) { this.value = value; }
    }

    static class WindowsDialog implements Dialog {
        @Override
        public void render() {
            System.out.println("    [Windows Dialog] Rendering modal dialog with sharp corners");
        }
        @Override
        public void close() {
            System.out.println("    [Windows Dialog] Closing with fade-out animation");
        }
    }


    // =========================================================================
    // STEP 2 (continued): CONCRETE PRODUCTS — Mac Family
    // All Mac products are consistent with each other, but different from Windows.
    // =========================================================================

    static class MacButton implements Button {
        @Override
        public void render() {
            System.out.println("    [Mac Button] Rendering rounded pill-shaped button");
        }
        @Override
        public void onClick() {
            System.out.println("    [Mac Button] Click → subtle fade effect → action triggered");
        }
    }

    static class MacTextField implements TextField {
        private String value = "";

        @Override
        public void render() {
            System.out.println("    [Mac TextField] Rendering rounded-corner input with blue focus ring");
        }
        @Override
        public String getValue() { return value; }
        @Override
        public void setValue(String value) { this.value = value; }
    }

    static class MacDialog implements Dialog {
        @Override
        public void render() {
            System.out.println("    [Mac Dialog] Rendering sheet dialog that slides down from title bar");
        }
        @Override
        public void close() {
            System.out.println("    [Mac Dialog] Sheet slides back up and disappears");
        }
    }


    // =========================================================================
    // STEP 2 (continued): CONCRETE PRODUCTS — Dark Theme Family
    // A third family — showing how easy it is to add one without touching clients.
    // =========================================================================

    static class DarkThemeButton implements Button {
        @Override
        public void render() {
            System.out.println("    [Dark Button] Rendering dark gray button with neon border");
        }
        @Override
        public void onClick() {
            System.out.println("    [Dark Button] Click → neon glow pulse effect");
        }
    }

    static class DarkThemeTextField implements TextField {
        private String value = "";

        @Override
        public void render() {
            System.out.println("    [Dark TextField] Rendering dark input field with cyan text cursor");
        }
        @Override
        public String getValue() { return value; }
        @Override
        public void setValue(String value) { this.value = value; }
    }

    static class DarkThemeDialog implements Dialog {
        @Override
        public void render() {
            System.out.println("    [Dark Dialog] Rendering dark overlay with frosted glass center panel");
        }
        @Override
        public void close() {
            System.out.println("    [Dark Dialog] Panel collapses with elastic animation");
        }
    }


    // =========================================================================
    // STEP 3: ABSTRACT FACTORY INTERFACE
    // Declares one creation method per product type. Every concrete factory
    // must produce all three products.
    // =========================================================================
    interface UIFactory {
        Button createButton();
        TextField createTextField();
        Dialog createDialog();
        String getThemeName(); // for display purposes
    }


    // =========================================================================
    // STEP 4: CONCRETE FACTORIES — one per product family
    // Each factory returns ONLY its own family's products.
    // This is the guarantee: if you use MacUIFactory, you CANNOT accidentally
    // get a Windows component.
    // =========================================================================

    static class WindowsUIFactory implements UIFactory {
        @Override public Button    createButton()    { return new WindowsButton(); }
        @Override public TextField createTextField() { return new WindowsTextField(); }
        @Override public Dialog    createDialog()    { return new WindowsDialog(); }
        @Override public String    getThemeName()    { return "Windows"; }
    }

    static class MacUIFactory implements UIFactory {
        @Override public Button    createButton()    { return new MacButton(); }
        @Override public TextField createTextField() { return new MacTextField(); }
        @Override public Dialog    createDialog()    { return new MacDialog(); }
        @Override public String    getThemeName()    { return "Mac"; }
    }

    static class DarkThemeUIFactory implements UIFactory {
        @Override public Button    createButton()    { return new DarkThemeButton(); }
        @Override public TextField createTextField() { return new DarkThemeTextField(); }
        @Override public Dialog    createDialog()    { return new DarkThemeDialog(); }
        @Override public String    getThemeName()    { return "Dark Theme"; }
    }


    // =========================================================================
    // CLIENT CODE: LoginScreen
    //
    // KEY INSIGHT: LoginScreen has ZERO knowledge of Mac, Windows, or Dark Theme.
    // It only depends on the UIFactory interface and the product interfaces.
    // The same LoginScreen works for all three themes without any modification.
    //
    // This is what "program to an interface, not an implementation" looks like.
    // =========================================================================
    static class LoginScreen {
        private final Button loginButton;
        private final TextField usernameField;
        private final TextField passwordField;
        private final Dialog errorDialog;
        private final String theme;

        // Constructor takes a factory — NOT a MacUIFactory or WindowsUIFactory.
        // This makes LoginScreen completely decoupled from any specific theme.
        public LoginScreen(UIFactory factory) {
            this.theme = factory.getThemeName();

            // Gets a CONSISTENT family — all components guaranteed to be from same theme.
            // There's no way to accidentally mix Mac button with Windows dialog here.
            this.loginButton   = factory.createButton();
            this.usernameField = factory.createTextField();
            this.passwordField = factory.createTextField();
            this.errorDialog   = factory.createDialog();
        }

        public void render() {
            System.out.println("  [LoginScreen - " + theme + "] Rendering...");
            usernameField.render();
            passwordField.render();
            loginButton.render();
        }

        public void simulateLogin(String username, String badPassword) {
            usernameField.setValue(username);
            passwordField.setValue(badPassword);
            loginButton.onClick();

            // Simulate a failed login — show error dialog
            System.out.println("  [LoginScreen] Login failed — showing error");
            errorDialog.render();
            errorDialog.close();
        }
    }


    // =========================================================================
    // MAIN — demonstrates switching between full families
    // =========================================================================
    public static void main(String[] args) {

        System.out.println("=== Abstract Factory Pattern Demo ===\n");

        // In a real application, this would be read from:
        //   - System.getProperty("os.name") for OS detection
        //   - A user preference setting for theme
        //   - A config file
        String[] themes = {"MAC", "WINDOWS", "DARK"};

        for (String theme : themes) {
            System.out.println("─────────────────────────────────────────");
            System.out.println("  Theme: " + theme);
            System.out.println("─────────────────────────────────────────");

            // ONLY this line changes between themes.
            // Everything after it is identical — same LoginScreen, same method calls.
            UIFactory factory = createFactory(theme);

            // LoginScreen doesn't know which theme it's using.
            // It just works with whatever factory it receives.
            LoginScreen screen = new LoginScreen(factory);
            screen.render();
            System.out.println();
            screen.simulateLogin("akash", "wrong-password");
            System.out.println();
        }

        // ----- Demonstrating family consistency guarantee -----
        System.out.println("─────────────────────────────────────────");
        System.out.println("  Consistency Guarantee Demo");
        System.out.println("─────────────────────────────────────────");
        System.out.println("  All components from MacUIFactory are Mac:");
        UIFactory macFactory = new MacUIFactory();
        macFactory.createButton().render();
        macFactory.createTextField().render();
        macFactory.createDialog().render();
        System.out.println("  It's impossible to get a Windows component from MacUIFactory.");

        System.out.println("\n=== Key Takeaways ===");
        System.out.println("  1. Factory guarantees a consistent family — no accidental mixing");
        System.out.println("  2. Client (LoginScreen) has ZERO knowledge of Mac/Windows/Dark");
        System.out.println("  3. Adding a new theme = add 3 product classes + 1 factory class");
        System.out.println("  4. Existing client code is NEVER modified");
        System.out.println("  5. vs Factory Method: FM creates ONE product; AF creates a FAMILY");
    }

    // Factory selector — maps a string to the right factory
    // In practice this could live in a config or dependency injection setup
    static UIFactory createFactory(String theme) {
        switch (theme) {
            case "MAC":     return new MacUIFactory();
            case "WINDOWS": return new WindowsUIFactory();
            case "DARK":    return new DarkThemeUIFactory();
            default: throw new IllegalArgumentException("Unknown theme: " + theme);
        }
    }
}
