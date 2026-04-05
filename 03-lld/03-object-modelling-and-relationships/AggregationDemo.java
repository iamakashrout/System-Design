import java.util.ArrayList;
import java.util.List;

/**
 * RELATIONSHIP 3: AGGREGATION — "You're part of my team, but you'll survive without me"
 *
 * Definition:
 *   Class A CONTAINS or MANAGES a collection of Class B objects.
 *   B objects are created OUTSIDE A and passed in.
 *   B can exist without A — their lifecycles are independent.
 *   If A is destroyed, B survives and can be added to another container.
 *
 * Real-world analogy:
 *   A football team HAS players.
 *   Players are people who existed before joining the team.
 *   If the team is disbanded, the players don't vanish — they join other teams.
 *   The team doesn't "own" the players' existence.
 *
 * Code signal:
 *   - Objects are passed in via add(X x) methods — NOT created with new X() inside.
 *   - The container manages a collection but didn't create those objects.
 *   - Child objects have meaningful existence outside the parent.
 *
 * Aggregation vs Composition — the key test:
 *   "If I delete the parent, does the child logically stop existing?"
 *   NO → Aggregation
 *   YES → Composition
 *
 * UML notation: A ◇——→ B  (hollow diamond at A's end)
 */

// ─── Supporting Classes ──────────────────────────────────────────────────────

/**
 * Player is an independent entity.
 * Created outside any Team. Can move between teams. Survives team disbanding.
 */
class Player {
    private final String playerId;
    private final String name;
    private final String position;

    public Player(String playerId, String name, String position) {
        this.playerId = playerId;
        this.name = name;
        this.position = position;
    }

    public String getPlayerId() { return playerId; }
    public String getName() { return name; }
    public String getPosition() { return position; }

    @Override
    public String toString() {
        return "Player{id='" + playerId + "', name='" + name + "', position='" + position + "'}";
    }
}

/**
 * Professor is an independent entity.
 * Exists before joining a Department. Still exists after a Department is dissolved.
 * Can be shared between Departments (e.g., a visiting professor).
 */
class Professor {
    private final String professorId;
    private final String name;
    private final String subject;

    public Professor(String professorId, String name, String subject) {
        this.professorId = professorId;
        this.name = name;
        this.subject = subject;
    }

    public String getProfessorId() { return professorId; }
    public String getName() { return name; }
    public String getSubject() { return subject; }

    @Override
    public String toString() {
        return "Professor{id='" + professorId + "', name='" + name + "', subject='" + subject + "'}";
    }
}

// ─── Aggregation Example 1: Team ◇——→ Player ─────────────────────────────────

/**
 * Team AGGREGATES Player objects.
 *
 * Key observations:
 * 1. Players are passed IN via addPlayer() — Team does NOT do: new Player(...)
 * 2. Removing a player from the team doesn't destroy the player object
 * 3. A player could play for multiple teams (e.g., national + club team)
 * 4. If Team is garbage collected, Player objects are unaffected
 */
class Team {
    private final String teamName;
    private final String sport;

    // AGGREGATION: Team manages a collection of Player references
    // These players were created outside and passed in
    private final List<Player> players;

    public Team(String teamName, String sport) {
        this.teamName = teamName;
        this.sport = sport;
        this.players = new ArrayList<>();
    }

    /**
     * AGGREGATION in action: Player is passed in from outside.
     * Team says "I'll manage this player" but doesn't claim ownership of their existence.
     */
    public void addPlayer(Player player) {
        players.add(player);
        System.out.println("[Team] " + player.getName() + " joined " + teamName);
    }

    /**
     * Removing from the team — player still exists, just no longer in this team.
     * This is the core aggregation property: child outlives parent.
     */
    public void removePlayer(Player player) {
        players.remove(player);
        System.out.println("[Team] " + player.getName() + " left " + teamName);
        System.out.println("       (Player object still alive — can join another team)");
    }

    public void listPlayers() {
        System.out.println("[Team] " + teamName + " squad (" + players.size() + " players):");
        for (Player p : players) {
            System.out.println("   → " + p);
        }
    }

    public void disband() {
        System.out.println("[Team] " + teamName + " is being disbanded.");
        System.out.println("       Players are NOT destroyed — they outlive the team.");
        players.clear(); // team no longer holds references, but player objects still exist
    }

    public String getTeamName() { return teamName; }
}

// ─── Aggregation Example 2: Department ◇——→ Professor ────────────────────────

/**
 * Department AGGREGATES Professor objects.
 * Classic university example — professors predate and outlive departments.
 * A visiting professor can even be shared between two departments.
 */
class Department {
    private final String departmentName;

    // AGGREGATION: professors are passed in, not created here
    private final List<Professor> professors;

    public Department(String departmentName) {
        this.departmentName = departmentName;
        this.professors = new ArrayList<>();
    }

    /**
     * Professor passed in from outside — Department didn't create this professor.
     */
    public void addProfessor(Professor prof) {
        professors.add(prof);
        System.out.println("[Department] Prof." + prof.getName()
                + " added to " + departmentName + " dept.");
    }

    public void removeProfessor(Professor prof) {
        professors.remove(prof);
        System.out.println("[Department] Prof." + prof.getName()
                + " left " + departmentName + " dept.");
    }

    public void listProfessors() {
        System.out.println("[Department] " + departmentName + " faculty:");
        for (Professor p : professors) {
            System.out.println("   → " + p);
        }
    }

    public String getName() { return departmentName; }
}

// ─── Runner ───────────────────────────────────────────────────────────────────

public class AggregationDemo {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  AGGREGATION RELATIONSHIP DEMO");
        System.out.println("========================================\n");

        // --- Example 1: Team ◇——→ Player ---
        System.out.println("--- Example 1: Team ◇——→ Player ---\n");

        // Players exist independently — no Team involved in their creation
        Player rohan   = new Player("P01", "Rohan",   "Forward");
        Player vikram  = new Player("P02", "Vikram",  "Midfielder");
        Player priya   = new Player("P03", "Priya",   "Goalkeeper");
        Player akash   = new Player("P04", "Akash",   "Defender");

        // Teams also exist independently
        Team teamA = new Team("FC Warriors", "Football");
        Team teamB = new Team("City United", "Football");

        // Aggregation: existing players are added to teams
        teamA.addPlayer(rohan);
        teamA.addPlayer(vikram);
        teamA.addPlayer(priya);
        teamA.addPlayer(akash);

        System.out.println();
        teamA.listPlayers();

        System.out.println();
        // Akash moves to teamB — he can, because neither team "owns" him
        teamA.removePlayer(akash);
        teamB.addPlayer(akash);
        System.out.println("   akash is still alive: " + akash); // player object unchanged

        System.out.println();
        // Disband teamA — players survive
        teamA.disband();
        System.out.println("   rohan after team disbanded: " + rohan); // still alive
        System.out.println("   vikram after team disbanded: " + vikram); // still alive

        System.out.println("\n--- Example 2: Department ◇——→ Professor ---\n");

        // Professors exist independently
        Professor drSingh  = new Professor("PR01", "Dr. Singh",  "Algorithms");
        Professor drGupta  = new Professor("PR02", "Dr. Gupta",  "Databases");
        Professor drPatel  = new Professor("PR03", "Dr. Patel",  "Networks");

        // Departments also exist independently
        Department csDept   = new Department("Computer Science");
        Department itDept   = new Department("Information Technology");

        csDept.addProfessor(drSingh);
        csDept.addProfessor(drGupta);
        itDept.addProfessor(drPatel);

        // Visiting professor — shared between two departments
        // This is possible in Aggregation — same object in two containers
        System.out.println("\n[Demo] Dr. Singh becomes a visiting professor for IT dept too:");
        itDept.addProfessor(drSingh);

        System.out.println();
        csDept.listProfessors();
        System.out.println();
        itDept.listProfessors();

        System.out.println();
        // CS dept is dissolved — professors survive and can move elsewhere
        System.out.println("[Demo] CS Department is dissolved:");
        csDept.removeProfessor(drSingh);
        csDept.removeProfessor(drGupta);
        System.out.println("   drSingh still exists: " + drSingh);
        System.out.println("   drGupta still exists: " + drGupta);

        System.out.println("\n--- Key Takeaway ---");
        System.out.println("Aggregation = HAS-A, but child is PASSED IN and outlives parent.");
        System.out.println("Parent manages the collection but does NOT own the lifecycle.");
        System.out.println("Children can be shared across multiple parents simultaneously.");
    }
}
