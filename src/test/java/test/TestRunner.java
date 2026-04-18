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
            test.marsNorthPoleReachableViaAerobrakeWithLowThrust();
            System.out.println("PASS: marsNorthPoleReachableViaAerobrakeWithLowThrust");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: marsNorthPoleReachableViaAerobrakeWithLowThrust — " + e.getMessage());
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

        try {
            test.decorativeChainBidirectional();
            System.out.println("PASS: decorativeChainBidirectional");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: decorativeChainBidirectional — " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        try {
            test.engineSwitchingOffersRadiationMitigationRoutes();
            System.out.println("PASS: engineSwitchingOffersRadiationMitigationRoutes");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: engineSwitchingOffersRadiationMitigationRoutes — " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        try {
            test.solarModLoadsFromJson();
            System.out.println("PASS: solarModLoadsFromJson");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: solarModLoadsFromJson — " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        try {
            test.solarEngineInOuterZoneHasReducedReachability();
            System.out.println("PASS: solarEngineInOuterZoneHasReducedReachability");
            passed++;
        } catch (Throwable e) {
            System.out.println("FAIL: solarEngineInOuterZoneHasReducedReachability — " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        System.out.println("\n" + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
