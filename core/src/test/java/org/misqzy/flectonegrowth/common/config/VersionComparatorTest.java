package org.misqzy.flectonegrowth.common.config;

import org.junit.jupiter.api.Test;
import org.misqzy.flectonegrowth.common.config.migration.VersionComparator;

import static org.junit.jupiter.api.Assertions.*;

class VersionComparatorTest {

    @Test
    void comparesNumericallyNotLexically() {
        assertTrue(VersionComparator.compare("0.1.10", "0.1.2") > 0);
        assertTrue(VersionComparator.compare("0.2.9", "0.2.10") < 0);
        assertTrue(VersionComparator.compare("1.0.0", "0.99.99") > 0);
    }

    @Test
    void missingSegmentsAreZero() {
        assertEquals(0, VersionComparator.compare("0.1", "0.1.0"));
        assertEquals(0, VersionComparator.compare("1", "1.0.0.0"));
        assertTrue(VersionComparator.compare("0.1", "0.1.1") < 0);
    }

    @Test
    void malformedSegmentsDegradeToZero() {
        assertEquals(0, VersionComparator.compare("0.x.1", "0.0.1"));
        assertEquals(0, VersionComparator.compare("0.2.10-SNAPSHOT", "0.2.0"));
        assertEquals(0, VersionComparator.compare("", "0"));
    }

    @Test
    void isAntisymmetric() {
        assertEquals(-Integer.signum(VersionComparator.compare("0.2.10", "0.3.0")),
                Integer.signum(VersionComparator.compare("0.3.0", "0.2.10")));
    }
}
