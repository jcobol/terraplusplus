package io.github.terra121.dataset.impl;

import io.github.terra121.util.RandomAccessRunlength;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public class WaterGround {
    public final RandomAccessRunlength<Byte> data = new RandomAccessRunlength<>();
    private final int width;
    private final int height;

    public WaterGround(InputStream input) throws IOException {
        try (DataInputStream in = new DataInputStream(WaterGround.class.getResourceAsStream("/assets/terra121/data/ground.dat"))) {
            //save some memory by tying the same bytes to the same object (idk if java does this already) //TODO: static share with Soil.java
            Byte[] bytes = new Byte[256];
            for (int x = 0; x < bytes.length; x++) {
                bytes[x] = (byte) x;
            }

            while (in.available() > 0) {
                int v = in.readInt();
                this.data.addRun(bytes[v >>> 30], v & ((1 << 30) - 1));
            }
        }

        this.height = (int) Math.sqrt(this.data.size() * 0.5d);
        this.width = this.height * 2;
    }

    public byte getOfficial(int x, int y) {
        if (x >= this.width || x < 0 || y >= this.height || y < 0) {
            return 0;
        }
        return this.data.get(x + y * this.width);
    }

    public byte state(int x, int y) {
        return this.getOfficial(x + (this.width / 2), y + (this.height / 2));
    }
}
