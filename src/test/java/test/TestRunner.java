package test;

/** Minimal test runner — invokes JUnit tests from main(). */
public class TestRunner {
    public static void main(String[] args) {
        PathfinderTest test = new PathfinderTest();
        PathfinderTest.loadMap();
        int passed = 0, failed = 0;

        try {
            test.arsiaMonsReachableViaAerobrake();
            System.out.println("PASS: arsiaMonsReachableViaAerobrake");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: arsiaMonsReachableViaAerobrake — " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        try {
            test.marsNorthPoleUnreachableWithLowThrust();
            System.out.println("PASS: marsNorthPoleUnreachableWithLowThrust");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: marsNorthPoleUnreachableWithLowThrust — " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        try {
            test.marsNorthPoleReachableWithHighThrust();
            System.out.println("PASS: marsNorthPoleReachableWithHighThrust");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: marsNorthPoleReachableWithHighThrust — " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        System.out.println("\n" + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
