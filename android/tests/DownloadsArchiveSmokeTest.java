package cn.liuyingbin.ruiccards;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Host checks call the production scanner's ZipFile inspection without Android framework mocks. */
public final class DownloadsArchiveSmokeTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("downloads-archive-test-").toFile();
        try {
            File archive = new File(directory, "fixture.zip");
            zip(archive, "card-config.json", "assets/image.png");
            check(DownloadsScanner.inspect(archive).cardCount() == 1, "Single card recognized");
            zip(archive, "folder/card-config.json", "folder/assets/image.png");
            check(DownloadsScanner.inspect(archive).cardCount() == 1, "One outer directory recognized");
            zip(archive, "first.ruic", "folder/second.ruic", "README.txt", "SHA256SUMS", "ignored.zip");
            check(DownloadsScanner.inspect(archive).cardCount() == 2, "Collection discovered without opening nested ZIP");
            zip(archive, "notes.txt"); check(DownloadsScanner.inspect(archive) == null, "Ordinary ZIP excluded");
            zip(archive, "library-state.json", "first.ruic"); check(DownloadsScanner.inspect(archive) == null, "App backup excluded");
            for (String unsafe : new String[]{"../x.ruic", "/x.ruic", "folder\\x.ruic", "folder/%2e.ruic"}) {
                zip(archive, unsafe); check(rejected(archive), "Unsafe path rejected");
            }
            zip(archive, "first.ruic", "FIRST.RUIC"); check(rejected(archive), "Case-fold duplicates rejected");
            String[] tooMany = new String[1025]; for (int i = 0; i < tooMany.length; i++) tooMany[i] = i + ".json";
            zip(archive, tooMany); check(rejected(archive), "Entry count rejected before ZipFile allocation");
            zip(archive, "first.ruic"); patchEntrySizes(archive, CardStore.MAX_ENTRY + 1);
            check(rejected(archive), "96 MiB entry budget enforced from ZIP metadata");
            String[] expanded = new String[9]; for (int i = 0; i < expanded.length; i++) expanded[i] = i + ".ruic";
            zip(archive, expanded); patchEntrySizes(archive, CardStore.MAX_ENTRY);
            check(rejected(archive), "768 MiB expanded budget enforced from ZIP metadata");
            zip(archive, "first.ruic"); byte[] bytes = Files.readAllBytes(archive.toPath());
            Files.write(archive.toPath(), Arrays.copyOf(bytes, bytes.length - 1));
            check(rejected(archive), "Incomplete ZIP end record rejected");
            CardStore.SharedImportBudget shared = new CardStore.SharedImportBudget();
            shared.reserve(CardStore.MAX_EXPANDED);
            boolean sharedRejected = false; try { shared.reserve(1); } catch (java.io.IOException expected) { sharedRejected = true; }
            check(sharedRejected, "Shared collection byte budget cannot be reset by another package");
            System.out.println("Production Download archive inspection: " + checks + " checks passed");
        } finally {
            File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete();
        }
    }
    private static void patchEntrySizes(File archive, long size) throws Exception {
        byte[] bytes = Files.readAllBytes(archive.toPath());
        for (int i = 0; i + 46 <= bytes.length; i++) if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2)
            for (int part = 0; part < 4; part++) bytes[i + 24 + part] = (byte) (size >>> (8 * part));
        Files.write(archive.toPath(), bytes);
    }
    private static boolean rejected(File archive) { try { DownloadsScanner.inspect(archive); return false; } catch (Exception expected) { return true; } }
    private static void check(boolean condition, String description) { if (!condition) throw new AssertionError(description); checks++; }
    private static void zip(File archive, String... names) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1, 2, 3}); zip.closeEntry(); }
        }
    }
}
