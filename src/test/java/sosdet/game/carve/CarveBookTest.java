package sosdet.game.carve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class CarveBookTest {

    private static CarveRecord carve(int region, int cx, int cy) {
        CarveRecord c = new CarveRecord(region, cx, cy);
        for (int k = 0; k < CarveRecord.SQUARE; k++) {
            c.square(k, (cy - 1 + k / 3) * 256 + cx - 1 + k % 3, k == 4 ? -1 : 22);
        }
        c.road(1000);
        c.road(1001);
        c.path(1000, 0, 0b0001_0000);
        c.path(1001, 0b0000_0001, 0b0001_0001);
        c.linked(22);
        c.linked(14);
        return c;
    }

    @Test
    void roundTripKeepsEveryRecord() throws IOException {
        CarveBook b = new CarveBook();
        CarveRecord a = carve(256, 128, 2);
        a.newNpc(40);
        b.add(a);
        b.add(carve(257, 140, 20));
        CarveBook back = CarveBook.fromInts(b.toInts());
        assertEquals(b.all(), back.all());
        assertEquals(b.hash(1), back.hash(1));
        assertEquals(40, back.find(256).newNpc());
        assertEquals(22, back.find(256).previous(0));
        assertEquals(-1, back.find(256).previous(4), "a tile no region held");
    }

    @Test
    void anEmptyBookRoundTrips() throws IOException {
        assertTrue(CarveBook.fromInts(new CarveBook().toInts()).empty());
    }

    @Test
    void aBookOfAnotherVersionOrCutShortIsRefused() {
        int[] v = new CarveBook().toInts();
        v[0] = 99;
        assertThrows(IOException.class, () -> CarveBook.fromInts(v));
        CarveBook b = new CarveBook();
        b.add(carve(256, 128, 2));
        int[] w = b.toInts();
        assertThrows(IOException.class, () -> CarveBook.fromInts(Arrays.copyOf(w, w.length - 1)));
        int[] x = b.toInts();
        x[2] += 5; // the record says it is longer than the book
        assertThrows(IOException.class, () -> CarveBook.fromInts(x));
    }

    @Test
    void aRegionIsCarvedOnce() {
        CarveBook b = new CarveBook();
        b.add(carve(256, 128, 2));
        assertThrows(IllegalArgumentException.class, () -> b.add(carve(256, 50, 50)));
    }

    @Test
    void unCarvingLeavesWhatAnotherCarveUses() {
        CarveBook b = new CarveBook();
        CarveRecord first = carve(256, 128, 2);
        CarveRecord second = new CarveRecord(257, 140, 20);
        second.road(1001);
        second.path(1001, 0b0001_0001, 0b0011_0001); // added a bit on top of the first's
        second.path(1000, 0b0001_0000, 0b0001_0000);
        b.add(first);
        b.add(second);
        assertTrue(b.roadToTakeOut(first, 1000));
        assertFalse(b.roadToTakeOut(first, 1001), "the second carve's link runs on it too");
        assertEquals(0b0001_0000, b.bitsToTakeOut(first, 1000));
        assertEquals(0b0001_0000, b.bitsToTakeOut(first, 1001));
        assertEquals(0b0010_0000, b.bitsToTakeOut(second, 1001));
        b.remove(first);
        assertNull(b.find(256));
        assertTrue(b.roadToTakeOut(second, 1001));
    }

    @Test
    void theHashSeesEveryChange() {
        CarveBook a = new CarveBook();
        a.add(carve(256, 128, 2));
        CarveBook b = new CarveBook();
        CarveRecord c = carve(256, 128, 2);
        c.newNpc(3);
        b.add(c);
        assertNotEquals(a.hash(1), b.hash(1));
        assertNotEquals(new CarveBook().hash(1), a.hash(1));
    }

    @Test
    void aTileChangedTwiceKeepsItsFirstBefore() {
        CarveRecord c = new CarveRecord(256, 10, 10);
        c.path(5, 0b0001, 0b0011);
        c.path(5, 0b0011, 0b0111);
        assertEquals(0b0110, c.addedBits(5));
    }
}
