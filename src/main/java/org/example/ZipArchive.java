package org.example;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;
import lombok.Setter;
import org.fusesource.jansi.Ansi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipArchive {
    static List<String> excludedPathAndFiles;
    static DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    static DateTimeFormatter showDateTime = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    static LocalDateTime today = LocalDateTime.now();
    private static final int MODIFIED_BEFORE_HOURS = 2;
    static LocalDateTime beforeDate = today.minusHours(MODIFIED_BEFORE_HOURS);
    private static final Set<String> pathSet = new HashSet<>();
    private static final int MAX_QUEUE = 5;
    private static final Dotenv dotenv = Dotenv.configure()
            .directory(System.getProperty("user.dir") + System.getProperty("file.separator") + ".env")
            .ignoreIfMalformed()
            .ignoreIfMissing()
            .load();
    private static final String PREFIX_ZIPPED_FILES = dotenv.get("PREFIX_ZIPPED_FILES");
    private static final File MAIN_PATH = new File(Objects.requireNonNull(dotenv.get("MAIN_PATH")));
    private static final String END_FILE = "end";

    public static void main(String[] args) {

        System.out.println(Ansi.ansi().fg(Ansi.Color.BLUE).a("Wyszukuje pliki zmodyfikowane później niż '" + beforeDate.format(showDateTime) + "'").reset());

        excludedPathAndFiles = loadExcludes(dotenv.get("EXCLUDED_PATHS_FILE"));

        BlockingQueue<String> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
        new Thread(new SearcherFiles(MAIN_PATH, queue)).start();
        for (int i = 0; i < MAX_QUEUE; i++) {
            new Thread(new SearcherMatchingModifiedDateInFiles(queue)).start();
        }
    }

    @Nullable
    private static List<String> loadExcludes(String filePath) {
        try {
            FileReader reader = new FileReader(filePath);
            List<String> excludes = new Gson().fromJson(reader, new TypeToken<ArrayList<String>>() {
            }.getType());
            reader.close();
            return excludes;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Dodaje predefiniowane wartości do przekazanej listy excludes, konwertuje listę na format JSON, a następnie zapisuje wynikowy ciąg znaków JSON do pliku o nazwie "excludes.json".
     *
     * @param excludes - lista ścieżek i plików do wykluczenia z przeszukiwania
     */
    private static void saveExcludes(@NotNull List<String> excludes) {
        excludes.add("modules");
        excludes.add("target");
        excludes.add(".idea");
        excludes.add("node_modules");
        excludes.add("TestJava");

        Gson gson = new Gson();
        String json = gson.toJson(excludedPathAndFiles);

        try {
            FileWriter writer = new FileWriter("excludes.json");
            writer.write(json);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class SearcherFiles implements Runnable {

        File searchingPath;
        BlockingQueue<String> queue;

        public SearcherFiles(File searchingPath, BlockingQueue<String> queue) {
            this.searchingPath = searchingPath;
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                searcherPath(searchingPath, "");
                queue.put(END_FILE);
            } catch (InterruptedException e) {
                System.out.println("Błąd przeszukiwacza ścieżek: " + e.getMessage());
            }
        }

        /**
         * Przeszukuje rekurencyjnie katalogi wskazane przez argument 'path', szukając plików zmodyfikowanych między zadanymi datami,
         * które nie należą do wykluczonych ścieżek, i dodaje unikalne zmodyfikowane pliki do kolejki kompresji.
         *
         * @param path     - ścieżka do pliku aktualnie przeszukiwanego
         * @param mainPath - główna ścieżka przeznaczona do zapisu w pliku zip
         * @throws InterruptedException - wyjątek wątku
         */
        public void searcherPath(@NotNull File path, String mainPath) throws InterruptedException {
            String filePath;
            File[] paths = path.listFiles();
            if (paths != null) {
                for (File file : paths) {
                    filePath = mainPath.isEmpty() ? file.getPath() : mainPath;
                    if (file.isDirectory()) {
//                    System.out.println("Przeszukuję katalog: " + file.getPath());
                        searcherPath(file, filePath);
                    } else {
                        boolean isPathFound = false;
                        for (String ex : excludedPathAndFiles) {
                            if (file.getPath().contains(ex)) {
                                isPathFound = true;
                                break;
                            }
                        }
                        if (!isPathFound && isFileModifyDateBetweenDates(file.getPath(), beforeDate, today)) {
//                            System.out.println("Zmodyfikowany plik: '" + file.getPath() + "'");
                            if (!pathSet.contains(filePath)) {
                                System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Zmodyfikowany plik '" + file.getPath() + "' "
                                        + LocalDateTime.ofInstant(new Date(file.lastModified()).toInstant(), ZoneId.systemDefault()).format(showDateTime)
                                        + ", więc będę kompresował '" + filePath + "'...").reset());
                                pathSet.add(filePath);
                                queue.put(filePath);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    static class SearcherMatchingModifiedDateInFiles implements Runnable {

        BlockingQueue<String> queue;

        public SearcherMatchingModifiedDateInFiles(BlockingQueue<String> queue) {
            this.queue = queue;
        }


        /**
         * wyszukuje i kompresuje pliki z kolejki aż do napotkania sygnału zakończenia, jednocześnie wypisując informacje podsumowujące kompresję.
         */
        @Override
        public void run() {
            try {
                boolean isSearchingFinished = false;
                while (!isSearchingFinished) {
                    String searchingFile = queue.take();
//                    System.out.println("Pobieram z kolejki plik: " + searchingFile.getName());
                    if (searchingFile.equals(END_FILE)) {
                        isSearchingFinished = true;
                        queue.put(END_FILE);
                    } else {
                        System.out.println("Kompresuje '" + searchingFile + "'...");
                        compressToZipDirectory zip = new compressToZipDirectory(searchingFile);
                        System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Zakończyłem kompresję '" + searchingFile + "', rozmiar przed kompresją: "
                                + zip.getFileSpace() / 1000 + " KB ,rozmiar po kompresji: "
                                + zip.getFileZipSpace() / 1000 + " KB").reset());
                    }
                }
            } catch (Exception e) {
                System.out.println("Błąd przeszukiwania pliku: " + e.getMessage());
            }
        }
    }

    /**
     * Sprawdza, czy plik został zmodyfikowany między podanymi datami.
     * Jeśli data końcowa jest pusta, sprawdza czy plik został zmodyfikowany po podanej dacie początkowej.
     * Jeśli data początkowa jest pusta, sprawdza czy plik został zmodyfikowany przed podaną datą końcową.
     *
     * @param filePath  - ścieżka do pliku
     * @param beginDate - data początkowa
     * @param endDate   - data końcowa
     * @return - true jeśli plik został zmodyfikowany między podanymi datami, false w przeciwnym wypadku
     */
    public static boolean isFileModifyDateBetweenDates(String filePath, @NotNull LocalDateTime beginDate, LocalDateTime endDate) {
        LocalDateTime lastModifyDate = LocalDateTime.ofInstant(new Date(new File(filePath).lastModified()).toInstant(), ZoneId.systemDefault());
        return (beginDate.isBefore(lastModifyDate) && endDate.isAfter(lastModifyDate));
    }

    @Getter
    @Setter
    static class compressToZipDirectory {
        ZipOutputStream zos;
        File srcDir;
        FileOutputStream fos;
        Long fileSpace;
        Long fileZipSpace;

        public compressToZipDirectory(@NotNull String dir) {
            if (dir.isBlank()) System.out.println("Brak katalogów do kompresji");
            try {
                this.fileSpace = 0L;
                this.fileZipSpace = 0L;
                this.srcDir = new File(dir);
                this.fos = new FileOutputStream(PREFIX_ZIPPED_FILES + srcDir.getName() + "_" + today.format(dateTimeFormatter) + ".zip");
                this.zos = new ZipOutputStream(new BufferedOutputStream(fos));
                addDirectory(srcDir, srcDir);
                this.zos.close();
                this.fos.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public void addDirectory(File baseDir, @NotNull File dir) throws IOException {
            File[] files = dir.listFiles();
            assert files != null;
            for (File file : files) {
                if (file.isDirectory()) {
                    addDirectory(baseDir, file);
                    continue;
                }

                String name = file.getCanonicalPath().substring(baseDir.getCanonicalPath().length() + 1);
                boolean isPathFound = false;
                for (String ex : excludedPathAndFiles) {
                    if (name.contains(ex)) {
                        isPathFound = true;
//                        System.out.println("wykluczam: " + file.getCanonicalPath());
                        break;
                    }
                }
                if (isPathFound) continue;

                FileInputStream fis = new FileInputStream(file);
                ZipEntry zipEntry = new ZipEntry(name);
                zos.putNextEntry(zipEntry);

                byte[] bytes = new byte[4096];
                int length;
//                System.out.print(file.getPath() + " ");
                while ((length = fis.read(bytes)) >= 0) {
                    zos.write(bytes, 0, length);
                    fileSpace += length;
//                    System.out.print(".");
                }
                zos.closeEntry();
                fileZipSpace += zipEntry.getCompressedSize();
                fis.close();
            }
        }
    }
}
