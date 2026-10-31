package cn.liuyingbin.ruiccards;

/** Host-side smoke checks for archive paths and the data-only import boundary. */
public final class ImportPolicySmokeTest {
    public static void main(String[] args) throws Exception {
        String[] rejected = {"../cover.png", "assets/../../private.png", "/cover.png", "C:/cover.png",
                "assets\\cover.png", "assets//cover.png", "./cover.png", "assets/%2e%2e/x.png", "cover.png?x=1", "assets/./x.png", "assets/"};
        for (String path : rejected) require(!CardStore.safeRelativePath(path), "Accepted unsafe path: " + path);
        for (String path : new String[]{"cover.png", "assets/subject.png", "cards/import_001/card.json", "one/assets/card.glb"}) {
            require(CardStore.safeRelativePath(path), "Rejected valid path: " + path);
            require(CardStore.allowedDataFile(path), "Rejected valid data: " + path);
        }
        for (String path : new String[]{"index.html", "app.js", "vector.svg", "lib.so", "card.png.exe", "worker.mjs"}) {
            require(!CardStore.allowedDataFile(path), "Accepted executable/non-data file: " + path);
        }
        require(!CardStore.validStateJsonSize(null), "Null backup state accepted");
        String asciiBoundary = "a".repeat(CardStore.MAX_STATE_BYTES);
        require(CardStore.validStateJsonSize(asciiBoundary), "Exact UTF-8 byte boundary rejected");
        require(!CardStore.validStateJsonSize(asciiBoundary + "a"), "Oversized ASCII state accepted");
        String chineseBoundary = "汉".repeat(CardStore.MAX_STATE_BYTES / 3);
        require(CardStore.validStateJsonSize(chineseBoundary + "a"), "Exact multibyte UTF-8 boundary rejected");
        require(!CardStore.validStateJsonSize(chineseBoundary + "汉"), "Oversized multibyte state accepted despite fewer characters");
        CardStore.BackupBudget entryBudget = new CardStore.BackupBudget(0);
        for (int i = 1; i < 1024; i++) entryBudget.addEntry(0);
        expectIo(() -> entryBudget.addEntry(0), "Oversized backup entry count accepted");
        long mib = 1024L * 1024L;
        CardStore.BackupBudget fileBudget = new CardStore.BackupBudget(0);
        fileBudget.addEntry(96 * mib);
        expectIo(() -> new CardStore.BackupBudget(96 * mib + 1), "Oversized backup file accepted");
        CardStore.BackupBudget totalBudget = new CardStore.BackupBudget(0);
        for (int i = 0; i < 8; i++) totalBudget.addEntry(96 * mib);
        expectIo(() -> totalBudget.addEntry(1), "Oversized expanded backup accepted");
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        CardStore.LimitedOutputStream limited = new CardStore.LimitedOutputStream(compressed, 3);
        limited.write(new byte[]{1, 2, 3});
        require(compressed.size() == 3, "Exact archive write boundary rejected");
        expectIo(() -> limited.write(4), "Oversized archive output accepted");
        require(compressed.size() == 3, "Rejected archive write crossed the output boundary");
        // Exercise the actual production ZIP reader, including paths and executable entries.
        java.lang.reflect.Method unpack = CardStore.class.getDeclaredMethod("unpackDataZip", java.io.File.class, java.io.File.class);
        unpack.setAccessible(true);
        for (String name : new String[]{"../escaped.json", "assets/../../escaped.json", "assets/app.js", "assets\\x.png", "assets/x.svg"}) {
            java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("ruic-import-check-");
            java.io.File zipFile = temp.resolve("input.zip").toFile();
            java.io.File target = temp.resolve("output").toFile(); target.mkdir();
            try {
                try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                }
                boolean rejectedZip = false;
                try { unpack.invoke(null, zipFile, target); }
                catch (java.lang.reflect.InvocationTargetException error) { rejectedZip = error.getCause() instanceof java.io.IOException; }
                require(rejectedZip, "Production ZIP reader accepted forbidden entry: " + name);
                require(!temp.resolve("escaped.json").toFile().exists(), "ZIP wrote outside target");
            } finally {
                try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(temp)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { java.nio.file.Files.delete(path); } catch (Exception ignored) {} });
                }
            }
        }
        System.out.println("Import policy: 25 path/type checks + 5 UTF-8 boundaries + 8 backup budget boundaries + 5 production ZIP attack cases passed");
    }
    @FunctionalInterface private interface IoAction { void run() throws java.io.IOException; }
    private static void expectIo(IoAction action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (java.io.IOException expected) { rejected = true; }
        require(rejected, message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
