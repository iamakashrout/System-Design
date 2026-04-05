import java.util.ArrayList;
import java.util.List;

/**
 * RELATIONSHIP 2: ASSOCIATION — "I know you, I keep your number"
 *
 * Definition:
 *   Class A holds a FIELD REFERENCE to Class B.
 *   The reference persists across method calls (unlike Dependency).
 *   But neither object OWNS the other — both exist independently.
 *   Their lifecycles are NOT tied together.
 *
 * Real-world analogy:
 *   A doctor has a list of patients stored in their system.
 *   The doctor knows patients over time — persists across visits.
 *   If the doctor retires, the patients don't disappear.
 *   If a patient moves cities, the doctor doesn't disappear.
 *   Neither creates the other. Neither owns the other.
 *
 * Code signal:
 *   The external class is stored as a FIELD.
 *   Objects are created OUTSIDE and passed IN (not created inside the class).
 *   Both can outlive each other.
 *
 * Dependency vs Association — the ONE clean rule:
 *   Field-level reference → Association (long-term relationship)
 *   Method parameter only → Dependency (short-term use)
 *
 * UML notation: A ——→ B  (solid arrow, no diamond)
 */

// ─── Supporting Classes ──────────────────────────────────────────────────────

/**
 * Patient is an independent entity.
 * It exists before any Doctor is created and after any Doctor retires.
 * It has no reference to Doctor — the association is one-directional here.
 */
class Patient {
    private final String patientId;
    private final String name;
    private final int age;

    public Patient(String patientId, String name, int age) {
        this.patientId = patientId;
        this.name = name;
        this.age = age;
    }

    public String getPatientId() { return patientId; }
    public String getName() { return name; }
    public int getAge() { return age; }

    @Override
    public String toString() {
        return "Patient{id='" + patientId + "', name='" + name + "', age=" + age + "}";
    }
}

/**
 * Course is an independent entity.
 * A course can exist without any student enrolled in it.
 * A student can be enrolled in many courses (and the student exists independently too).
 * This is a BIDIRECTIONAL association — both sides hold references.
 */
class Course {
    private final String courseId;
    private final String title;

    // Association: Course knows about its enrolled students
    private final List<Student> enrolledStudents;

    public Course(String courseId, String title) {
        this.courseId = courseId;
        this.title = title;
        this.enrolledStudents = new ArrayList<>();
    }

    // Students are passed in from outside — Course doesn't create them
    public void enrollStudent(Student student) {
        enrolledStudents.add(student);
        System.out.println("[Course] " + student.getName() + " enrolled in '" + title + "'");
    }

    public List<Student> getEnrolledStudents() { return enrolledStudents; }
    public String getCourseId() { return courseId; }
    public String getTitle() { return title; }
}

/**
 * Student is an independent entity.
 * It can exist without being enrolled in any course.
 * It holds references to courses it is enrolled in — bidirectional association.
 */
class Student {
    private final String studentId;
    private final String name;

    // Association: Student knows which courses it's enrolled in
    private final List<Course> courses;

    public Student(String studentId, String name) {
        this.studentId = studentId;
        this.name = name;
        this.courses = new ArrayList<>();
    }

    // Courses are passed in from outside — Student doesn't create them
    public void addCourse(Course course) {
        courses.add(course);
    }

    public List<Course> getCourses() { return courses; }
    public String getStudentId() { return studentId; }
    public String getName() { return name; }
}

// ─── Main Class: Doctor → Patients (One-directional Association) ──────────────

/**
 * Doctor ASSOCIATES WITH Patient objects.
 *
 * Key observations:
 * 1. `patients` is a FIELD — the relationship persists across method calls.
 * 2. Patients are created OUTSIDE Doctor and passed in via addPatient().
 * 3. If Doctor is "deleted", the Patient objects still exist in memory.
 * 4. A Patient could theoretically be associated with multiple Doctors.
 */
class Doctor {
    private final String doctorId;
    private final String name;
    private final String specialization;

    // ASSOCIATION: Doctor holds references to Patient objects
    // These patients were NOT created by Doctor
    private final List<Patient> patients;

    public Doctor(String doctorId, String name, String specialization) {
        this.doctorId = doctorId;
        this.name = name;
        this.specialization = specialization;
        this.patients = new ArrayList<>(); // empty list — patients come from outside
    }

    /**
     * Association in action: Patient is passed IN from outside.
     * Doctor does NOT do: new Patient(...) — that would suggest stronger ownership.
     */
    public void addPatient(Patient patient) {
        patients.add(patient);
        System.out.println("[Doctor] " + name + " now has patient: " + patient.getName());
    }

    public void removePatient(Patient patient) {
        patients.remove(patient);
        System.out.println("[Doctor] " + patient.getName() + " removed from Dr." + name + "'s list");
        System.out.println("         (Patient still exists — their lifecycle is independent)");
    }

    /**
     * Doctor uses Patient objects in methods — but already holds a field reference.
     * This is different from Dependency where there's no field.
     */
    public void writePrescription(Patient patient, String medicine) {
        System.out.println("[Doctor] Dr." + name + " prescribing " + medicine
                + " for " + patient.getName());
    }

    public void listPatients() {
        System.out.println("[Doctor] Dr." + name + "'s patients (" + patients.size() + "):");
        for (Patient p : patients) {
            System.out.println("   → " + p);
        }
    }

    public String getName() { return name; }
}

// ─── Runner ───────────────────────────────────────────────────────────────────

public class AssociationDemo {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  ASSOCIATION RELATIONSHIP DEMO");
        System.out.println("========================================\n");

        // --- Example 1: Doctor ↔ Patient (one-directional association) ---
        System.out.println("--- Example 1: Doctor → Patients ---\n");

        // Patients are created independently — no Doctor involved
        Patient p1 = new Patient("P001", "Akash", 26);
        Patient p2 = new Patient("P002", "Rahul", 30);
        Patient p3 = new Patient("P003", "Priya", 24);

        // Doctor is also created independently
        Doctor doctor = new Doctor("D001", "Sharma", "Cardiology");

        // Association is established by passing existing objects in
        doctor.addPatient(p1);
        doctor.addPatient(p2);
        doctor.addPatient(p3);

        System.out.println();
        doctor.listPatients();

        System.out.println();
        doctor.writePrescription(p1, "Aspirin 75mg");

        System.out.println();
        // Removing patient from doctor's list — patient STILL EXISTS
        doctor.removePatient(p2);
        System.out.println("   p2 object is still alive: " + p2);

        System.out.println("\n--- Example 2: Student ↔ Course (bidirectional association) ---\n");

        // Both exist independently
        Student s1 = new Student("S001", "Akash");
        Student s2 = new Student("S002", "Neha");

        Course c1 = new Course("CS101", "Data Structures");
        Course c2 = new Course("CS102", "System Design");

        // Association is established in both directions
        c1.enrollStudent(s1);
        c1.enrollStudent(s2);
        c2.enrollStudent(s1); // s1 is in both courses — neither course "owns" s1

        s1.addCourse(c1);
        s1.addCourse(c2);
        s2.addCourse(c1);

        System.out.println();
        System.out.println("[Student] " + s1.getName() + " is enrolled in "
                + s1.getCourses().size() + " course(s):");
        for (Course c : s1.getCourses()) {
            System.out.println("   → " + c.getTitle());
        }

        System.out.println("\n--- Key Takeaway ---");
        System.out.println("Association = field-level reference that persists.");
        System.out.println("Both objects are created independently and can outlive each other.");
        System.out.println("Neither creates the other. Neither owns the other.");
    }
}
