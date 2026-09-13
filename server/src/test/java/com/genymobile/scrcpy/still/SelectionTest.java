package com.genymobile.scrcpy.still;

import android.hardware.camera2.CameraMetadata;
import org.junit.Assert;
import org.junit.Test;

public class SelectionTest {

    @Test
    public void testLargest() {
        Assert.assertEquals(-1, Selection.largest(new int[0][]));
        Assert.assertEquals(1, Selection.largest(new int[][] {{4000, 3000}, {4080, 3060}, {3840, 2160}}));
        // same area: the wider one
        Assert.assertEquals(1, Selection.largest(new int[][] {{3000, 4000}, {4000, 3000}}));
    }

    @Test
    public void testPreview() {
        int[][] sizes = {{4080, 3060}, {1920, 1080}, {640, 480}, {320, 240}, {1280, 720}, {800, 600}};
        Assert.assertEquals(2, Selection.preview(sizes, 4080, 3060, 640));
        Assert.assertEquals(4, Selection.preview(sizes, 3840, 2160, 640));
        // no size of the JPEG's aspect ratio: the smallest wide enough one
        Assert.assertEquals(1, Selection.preview(new int[][] {{1000, 1000}, {700, 500}}, 4080, 3060, 640));
        // nothing wide enough: the largest
        Assert.assertEquals(0, Selection.preview(new int[][] {{320, 240}, {176, 144}}, 4080, 3060, 640));
    }

    @Test
    public void testClampFocus() {
        Assert.assertTrue(Float.isNaN(Selection.clampFocus(Float.NaN, 10f)));
        Assert.assertTrue(Float.isNaN(Selection.clampFocus(1.5f, null)));
        Assert.assertTrue(Float.isNaN(Selection.clampFocus(1.5f, 0f)));
        Assert.assertEquals(0f, Selection.clampFocus(-1f, 10f), 0);
        Assert.assertEquals(1.5f, Selection.clampFocus(1.5f, 10f), 0);
        Assert.assertEquals(20f, Selection.clampFocus(25f, 20f), 0);
    }

    @Test
    public void testFpsRange() {
        int[][] ranges = {{30, 30}, {15, 30}, {15, 15}, {24, 24}, {7, 30}};
        Assert.assertEquals(4, Selection.fpsRange(ranges, "lowest"));
        Assert.assertEquals(1, Selection.fpsRange(new int[][] {{15, 15}, {15, 30}}, "lowest"));
        Assert.assertEquals(0, Selection.fpsRange(ranges, "fixed:30"));
        Assert.assertEquals(3, Selection.fpsRange(ranges, "fixed:24"));
        Assert.assertEquals(1, Selection.fpsRange(ranges, "fixed:20"));
        Assert.assertEquals(-1, Selection.fpsRange(ranges, "fixed:60"));
        Assert.assertEquals(-1, Selection.fpsRange(ranges, "fixed:x"));
        Assert.assertEquals(-1, Selection.fpsRange(ranges, "bogus"));
        Assert.assertEquals(-1, Selection.fpsRange(new int[0][], "lowest"));
    }

    @Test
    public void testConverged() {
        int stationary = CameraMetadata.LENS_STATE_STATIONARY;
        int aeConverged = CameraMetadata.CONTROL_AE_STATE_CONVERGED;
        int awbConverged = CameraMetadata.CONTROL_AWB_STATE_CONVERGED;
        Assert.assertTrue(Selection.converged(stationary, aeConverged, awbConverged));
        Assert.assertTrue(Selection.converged(null, null, null));
        Assert.assertTrue(Selection.converged(stationary, CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED, awbConverged));
        Assert.assertTrue(Selection.converged(stationary, CameraMetadata.CONTROL_AE_STATE_LOCKED, CameraMetadata.CONTROL_AWB_STATE_LOCKED));
        Assert.assertFalse(Selection.converged(CameraMetadata.LENS_STATE_MOVING, aeConverged, awbConverged));
        Assert.assertFalse(Selection.converged(stationary, CameraMetadata.CONTROL_AE_STATE_SEARCHING, awbConverged));
        Assert.assertFalse(Selection.converged(stationary, CameraMetadata.CONTROL_AE_STATE_INACTIVE, awbConverged));
        Assert.assertFalse(Selection.converged(stationary, aeConverged, CameraMetadata.CONTROL_AWB_STATE_SEARCHING));
    }

    @Test
    public void testLocked() {
        Assert.assertTrue(Selection.locked(CameraMetadata.CONTROL_AE_STATE_LOCKED, CameraMetadata.CONTROL_AWB_STATE_LOCKED));
        Assert.assertTrue(Selection.locked(null, null));
        Assert.assertFalse(Selection.locked(CameraMetadata.CONTROL_AE_STATE_CONVERGED, CameraMetadata.CONTROL_AWB_STATE_LOCKED));
        Assert.assertFalse(Selection.locked(CameraMetadata.CONTROL_AE_STATE_LOCKED, CameraMetadata.CONTROL_AWB_STATE_CONVERGED));
    }

    @Test
    public void testValidName() {
        Assert.assertTrue(Selection.validName("RFGL74G6ESH-s01-a045"));
        Assert.assertFalse(Selection.validName(""));
        Assert.assertFalse(Selection.validName(null));
        Assert.assertFalse(Selection.validName("../x"));
        Assert.assertFalse(Selection.validName("a/b"));
        Assert.assertFalse(Selection.validName(".hidden"));
    }
}
