package com.github.yumeyucca.yumebox.loader;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PayloadMetadata {
    static final int MAGIC = 0x59445831;
    static final int VERSION = 3;
    static final String METADATA_ENTRY = "assets/loader/payload.bin";

    final String id;
    final String originalApplication;
    final String originalComponentFactory;
    final List<NativeEntry> nativeEntries;

    private PayloadMetadata(
            String id,
            String originalApplication,
            String originalComponentFactory,
            List<NativeEntry> nativeEntries
    ) {
        this.id = id;
        this.originalApplication = originalApplication;
        this.originalComponentFactory = originalComponentFactory;
        this.nativeEntries = Collections.unmodifiableList(nativeEntries);
    }

    static PayloadMetadata read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != MAGIC) {
            throw new IOException("Invalid loader payload magic");
        }
        int version = data.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported loader payload version: " + version);
        }
        String id = data.readUTF();
        String originalApplication = data.readUTF();
        String originalComponentFactory = data.readUTF();
        int nativeCount = data.readInt();
        if (nativeCount < 0 || nativeCount > 1000) {
            throw new IOException("Invalid payload native library count: " + nativeCount);
        }
        List<NativeEntry> nativeEntries = new ArrayList<>(nativeCount);
        for (int i = 0; i < nativeCount; i++) {
            String abi = data.readUTF();
            String assetName = data.readUTF();
            String outputName = data.readUTF();
            long size = data.readLong();
            byte[] sha256 = new byte[32];
            data.readFully(sha256);
            nativeEntries.add(new NativeEntry(abi, assetName, outputName, size, sha256));
        }
        return new PayloadMetadata(
                id,
                originalApplication,
                originalComponentFactory,
                nativeEntries
        );
    }

    record NativeEntry(String abi, String assetName, String outputName, long size, byte[] sha256) {
    }
}
