package test.rules;

import com.hf4all.planner.io.MapLoader;
import com.hf4all.planner.model.MapNode;
import com.hf4all.planner.model.NodeType;
import com.hf4all.planner.model.SolarMap;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Scenario-finder diagnostics. Every test here is {@code @Disabled} — enable
 * one and run it to discover node ids that match a given predicate. Useful
 * when adding a new rule test and needing a real example on the map.
 *
 * <p>These tests do no assertions; they just print to stdout. Intentionally
 * left in place as debugging aids.
 */
@Disabled("Diagnostic helpers — enable the class or an individual method to print candidate node ids")
class MapScenarioScanner {

    static SolarMap map() { return MapLoader.loadDefault(); }

    @Test
    void printAllFlybyNodes() {
        for (MapNode n : map().allNodes()) {
            if (n.type() == NodeType.FLYBY || n.type() == NodeType.VENUS) {
                System.out.printf("FLYBY id=%s type=%s boost=%d label=%s%n",
                        n.id(), n.type(), n.flybyBoost(), n.label());
            }
        }
    }

    @Test
    void printAllRadhazNodes() {
        for (MapNode n : map().allNodes()) {
            if (n.type() == NodeType.RADHAZ || n.radiation() > 0) {
                System.out.printf("RADHAZ id=%s radiation=%d label=%s%n",
                        n.id(), n.radiation(), n.label());
            }
        }
    }

    @Test
    void printSmallSites() {
        for (MapNode n : map().allNodes()) {
            if (n.isSite() && n.thrustRequired() > 0 && n.thrustRequired() <= 5) {
                System.out.printf("SITE id=%s size=%d label=%s%n",
                        n.id(), n.thrustRequired(), n.label());
            }
        }
    }

    @Test
    void printLandingBurns() {
        for (MapNode n : map().allNodes()) {
            if (n.isBurn() && !n.landing().isZero()) {
                System.out.printf("LANDING_BURN id=%s thrustRequired=%d landing=%s label=%s%n",
                        n.id(), n.thrustRequired(), n.landing(), n.label());
            }
        }
    }

    @Test
    void printHohmannsWithMultipleLabels() {
        SolarMap m = map();
        for (MapNode n : m.allNodes()) {
            if (!n.isHohmann()) continue;
            java.util.Set<String> labels = new java.util.LinkedHashSet<>();
            for (MapNode adj : m.neighboursOf(n)) {
                String label = m.edgeLabel(n, adj);
                if (label != null && !label.equals("0")) labels.add(label);
            }
            if (labels.size() >= 2) {
                System.out.printf("HOHMANN id=%s labels=%s degree=%d%n",
                        n.id(), labels, m.neighboursOf(n).size());
            }
        }
    }

    @Test
    void printOneWayEdges() {
        SolarMap m = map();
        for (MapNode from : m.allNodes()) {
            for (MapNode to : m.neighboursOf(from)) {
                if ("0".equals(m.edgeLabel(from, to))) {
                    System.out.printf("ONE_WAY from=%s(%s) -> to=%s(%s)%n",
                            from.id(), from.type(), to.id(), to.type());
                }
            }
        }
    }

    @Test
    void printVenusNodes() {
        for (MapNode n : map().allNodes()) {
            if (n.type() == NodeType.VENUS) {
                System.out.printf("VENUS id=%s boost=%d label=%s%n",
                        n.id(), n.flybyBoost(), n.label());
            }
        }
    }

    /** Print neighbours of a given node id — handy for understanding local topology. */
    @Test
    void printNeighboursOf334() {
        printNeighboursOf("334");
    }

    @Test
    void printFlybysWithBurnNeighbours() {
        SolarMap m = map();
        for (MapNode n : m.allNodes()) {
            if (n.type() != NodeType.FLYBY) continue;
            for (MapNode adj : m.neighboursOf(n)) {
                if (adj.isBurn()) {
                    System.out.printf("FLYBY_NEAR_BURN flyby=%s boost=%d burn=%s landing=%s thrReq=%d%n",
                            n.id(), n.flybyBoost(), adj.id(), adj.landing(), adj.thrustRequired());
                }
            }
        }
    }

    @Test
    void printSmallSiteApproaches() {
        SolarMap m = map();
        for (MapNode n : m.allNodes()) {
            if (!n.isSite() || n.thrustRequired() > 5 || n.thrustRequired() < 1) continue;
            System.out.printf("SITE id=%s size=%d label=%s%n",
                    n.id(), n.thrustRequired(), n.label());
            for (MapNode adj : m.neighboursOf(n)) {
                System.out.printf("  neighbour id=%s type=%s landing=%s edge(a,s)=%s edge(s,a)=%s%n",
                        adj.id(), adj.type(), adj.landing(),
                        m.edgeLabel(adj, n), m.edgeLabel(n, adj));
            }
        }
    }

    @Test
    void printNeighboursOfVenus() { printNeighboursOf("33"); }

    @Test
    void printNeighboursOfFlyby252() { printNeighboursOf("252"); }

    @Test
    void printNeighboursOfSphere7_669() { printNeighboursOf("669"); }

    @Test
    void printNeighboursOfHohmann5() { printNeighboursOf("5"); }

    @Test
    void printNeighboursOfEureka() { printNeighboursOf("4"); }

    @Test
    void printNeighboursOf969() { printNeighboursOf("969"); }

    @Test
    void printNeighboursOfMarsNP() { printNeighboursOf("340"); }

    @Test
    void printNeighboursOf901() { printNeighboursOf("901"); }

    @Test
    void printNeighboursOfRadhaz38() { printNeighboursOf("38"); }

    @Test
    void printNeighboursOfLagrange1() { printNeighboursOf("1"); }

    @Test
    void printNeighboursOfHohmann6() { printNeighboursOf("6"); }

    @Test
    void printNeighboursOf1412() { printNeighboursOf("1412"); }

    @Test
    void printNeighboursOf990() { printNeighboursOf("990"); }

    @Test
    void printNeighboursOf793() { printNeighboursOf("793"); }

    @Test
    void printNeighboursOf1459() { printNeighboursOf("1459"); }

    @Test
    void printNeighboursOf1174() { printNeighboursOf("1174"); }

    @Test
    void printNeighboursOf1175() { printNeighboursOf("1175"); }

    @Test
    void printNeighboursOfFlyby445() { printNeighboursOf("445"); }

    @Test
    void printNeighboursOfBurn37() { printNeighboursOf("37"); }

    @Test
    void printNeighboursOfBurn45() { printNeighboursOf("45"); }

    @Test
    void printNeighboursOfVenusChain() { printNeighboursOf("43"); }

    @Test
    void printNeighboursOf40() { printNeighboursOf("40"); }

    @Test
    void printFlybysNearLanderBurns() {
        SolarMap m = map();
        for (MapNode n : m.allNodes()) {
            if (n.type() != NodeType.FLYBY && n.type() != NodeType.VENUS) continue;
            // Two-hop search to see if any lander is within distance 2
            for (MapNode adj1 : m.neighboursOf(n)) {
                if (adj1.isBurn() && !adj1.landing().isZero()) {
                    System.out.printf("FLYBY->LANDER direct flyby=%s boost=%d lander=%s thrReq=%d%n",
                            n.id(), n.flybyBoost(), adj1.id(), adj1.thrustRequired());
                }
                for (MapNode adj2 : m.neighboursOf(adj1)) {
                    if (adj2.isBurn() && !adj2.landing().isZero()) {
                        System.out.printf("FLYBY->%s->LANDER flyby=%s via=%s(%s) lander=%s thrReq=%d%n",
                                adj1.type(), n.id(), adj1.id(), adj1.type(),
                                adj2.id(), adj2.thrustRequired());
                    }
                }
            }
        }
    }

    @Test
    void printNeighboursOfSphere0_39() { printNeighboursOf("39"); }

    @Test
    void printRadhazVariance() {
        java.util.Set<Integer> values = new java.util.TreeSet<>();
        for (MapNode n : map().allNodes()) {
            if (n.radiation() > 0) values.add(n.radiation());
        }
        System.out.println("distinct radiation values on map: " + values);
    }

    @Test
    void printConsecutiveFlybys() {
        SolarMap m = map();
        for (MapNode n : m.allNodes()) {
            if (n.type() != NodeType.FLYBY && n.type() != NodeType.VENUS) continue;
            for (MapNode adj1 : m.neighboursOf(n)) {
                if (adj1.isFlyby()) {
                    System.out.printf("FLYBY-FLYBY direct %s(%d) <-> %s(%d)%n",
                            n.id(), n.flybyBoost(), adj1.id(), adj1.flybyBoost());
                }
                for (MapNode adj2 : m.neighboursOf(adj1)) {
                    if (adj2.isFlyby() && !adj2.equals(n)) {
                        System.out.printf("FLYBY-%s-FLYBY %s(%d) via %s(%s) <-> %s(%d)%n",
                                adj1.type(), n.id(), n.flybyBoost(), adj1.id(), adj1.type(),
                                adj2.id(), adj2.flybyBoost());
                    }
                }
            }
        }
    }

    private static void printNeighboursOf(String id) {
        SolarMap m = map();
        MapNode center = m.nodeById(id);
        if (center == null) { System.out.println("unknown id: " + id); return; }
        System.out.printf("center: id=%s type=%s label=%s%n", center.id(), center.type(), center.label());
        for (MapNode adj : m.neighboursOf(center)) {
            System.out.printf("  -> %s type=%s label=%s edgeLabel(c,a)=%s edgeLabel(a,c)=%s%n",
                    adj.id(), adj.type(), adj.label(),
                    m.edgeLabel(center, adj), m.edgeLabel(adj, center));
        }
    }
}
