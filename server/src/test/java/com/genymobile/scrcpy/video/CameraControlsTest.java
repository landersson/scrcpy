package com.genymobile.scrcpy.video;

import org.junit.Assert;
import org.junit.Test;

public class CameraControlsTest {

    @Test
    public void testEmptyIsNull() {
        Assert.assertNull(CameraControls.parse(null));
        Assert.assertNull(CameraControls.parse(""));
    }

    @Test
    public void testModes() {
        CameraControls c = CameraControls.parse("nr=fast,edge=off");
        Assert.assertEquals(Integer.valueOf(1), c.noiseReduction);
        Assert.assertEquals(Integer.valueOf(0), c.edge);
        Assert.assertFalse(c.manualExposure());
        Assert.assertFalse(c.logAll);
    }

    @Test
    public void testUnsetModesStayNull() {
        CameraControls c = CameraControls.parse("edge=hq");
        Assert.assertNull(c.noiseReduction);
        Assert.assertEquals(Integer.valueOf(2), c.edge);
    }

    @Test
    public void testManualExposure() {
        CameraControls c = CameraControls.parse("exposure=2609,iso=25,log=all");
        Assert.assertTrue(c.manualExposure());
        Assert.assertEquals(2609, c.exposureUs);
        Assert.assertEquals(25, c.iso);
        Assert.assertTrue(c.logAll);
    }

    @Test
    public void testTonemapGamma() {
        Assert.assertEquals(2.4f, CameraControls.parse("tonemap-gamma=2.4").tonemapGamma, 1e-6f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExposureNeedsIso() {
        CameraControls.parse("exposure=2609");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownKey() {
        CameraControls.parse("awb=off");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownMode() {
        CameraControls.parse("nr=strong");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingValue() {
        CameraControls.parse("nr");
    }
}
