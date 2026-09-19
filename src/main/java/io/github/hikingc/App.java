package io.github.hikingc;

import com.drew.imaging.FileType;
import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.avi.AviDirectory;
import com.drew.metadata.eps.EpsDirectory;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.wav.WavDirectory;
import picocli.CommandLine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Parameters;

/// Reads multimedia and attempts to process it
@Command(name = "multorg", description = "Organize multimedia files based on their file metadata and EXIF tags")
public class App implements Callable<Integer> {
    private static final Logger LOGGER = Logger.getLogger(App.class.getName());
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd");
    private static final Set<String> IGNORED = Set.of("desktop.ini", "thumbs.db", ".ds_store");


    @Parameters(index = "0", description = "Input path directory.")
    private Path inputPath;

    @Parameters(index = "1", description = "Output path directory.")
    private Path outputPath;

    /// @param args Required args
    static void main(String[] args) {
        LOGGER.setLevel(Level.ALL);
        int exitCode = new CommandLine(new App()).execute(args);
        System.exit(exitCode);
    }

    static Optional<Instant> toInstant(Date d) {
        return Optional.ofNullable(d).map(Date::toInstant);
    }

    private static String safeName(String s) {
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) ? "Unknown" : cleaned;
    }

    @Override
    public Integer call() throws Exception {
        try (Stream<Path> stream = Files.walk(inputPath)) {
            stream.filter(Files::isRegularFile).filter(p -> !IGNORED.contains(p.getFileName().toString().toLowerCase()))
                    .forEach(path -> {
                        try {
                            Metadata metadata = ImageMetadataReader.readMetadata(path.toFile());
                            FileType fileTypeDetector = FileTypeDetector.detectFileType(new BufferedInputStream(Files.newInputStream(path)));
                            switch (fileTypeDetector) {
                                // Images that can carry EXIF
                                case Jpeg, Tiff, Png, WebP, Heif,
                                     Arw, Cr2, Crw, Crx, Nef, Orf, Raf, Rw2 -> {
                                    var exifData = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
                                    if (exifData != null) {
                                        String make = exifData.getString(ExifDirectoryBase.TAG_MAKE) != null ? exifData.getString(ExifDirectoryBase.TAG_MAKE).trim() : "Unknown";
                                        String model = exifData.getString(ExifDirectoryBase.TAG_MODEL) != null ? exifData.getString(ExifDirectoryBase.TAG_MODEL).trim() : "Unknown";
                                        Path finalPath = outputPath.resolve(safeName(make)).resolve(safeName(model));
                                        this.createDirs(finalPath);
                                        this.copyFile(path, finalPath.resolve(path.getFileName()));
                                    } else {
                                        handleDateCreation(path, metadata, AviDirectory.class, AviDirectory.TAG_DATETIME_ORIGINAL, "Other Images");
                                        LOGGER.log(Level.WARNING, "Image could not be processed: {0}", path.getFileName());
                                    }
                                }

                                case Avi ->
                                        handleDateCreation(path, metadata, AviDirectory.class, AviDirectory.TAG_DATETIME_ORIGINAL, "Videos");
                                case QuickTime ->
                                        handleDateCreation(path, metadata, QuickTimeDirectory.class, QuickTimeDirectory.TAG_CREATION_TIME, "Videos");
                                case Mp4 ->
                                        handleDateCreation(path, metadata, Mp4Directory.class, Mp4Directory.TAG_CREATION_TIME, "Videos");
                                case Eps ->
                                        handleDateCreation(path, metadata, EpsDirectory.class, EpsDirectory.TAG_CREATION_DATE, "Files");
                                case Wav ->
                                        handleDateCreation(path, metadata, WavDirectory.class, WavDirectory.TAG_DATE_CREATED, "Audio"); // somehow they got it
                                // None of these have any header tag about creation date
                                case Psd, Bmp, Gif, Ico, Pcx, Indd, Qxp, Flv, Asf, Mxf, Vob, Ram, Swf ->
                                        handleDateCreation(path, metadata, null, null, "Files");
                                // Audio
                                case Mp3, Aac -> handleDateCreation(path, metadata, null, null, "Audio");

                                // Documents and archives
                                case Pdf, Rtf, Zip, Sit, Sitx, Cfbf ->
                                        handleDateCreation(path, metadata, null, null, "Files");

                                // Can't tell from the header alone
                                case Riff, Unknown -> handleDateCreation(path, metadata, null, null, "Unknown Format");
                            }
                        } catch (IOException | ImageProcessingException e) {
                            LOGGER.log(Level.SEVERE, e, () -> "Failed to process " + path + ": " + e.getMessage());
                        }
                    });
        }
        return 0;
    }

    private <T extends Directory> void handleDateCreation(Path path, Metadata metadata,
                                                          Class<T> directoryClass, Integer creationTag, String nameDir) throws IOException {


        T directory = metadata.getFirstDirectoryOfType(directoryClass);

        Date created = (directory != null)
                ? directory.getDate(creationTag, TimeZone.getTimeZone("UTC"))
                : null;

        // MP4 stores an unset date as 1904, so treat anything before 1970 as missing
        if (created != null && created.toInstant().atZone(ZoneOffset.UTC).getYear() < 1970) {
            created = null;
        }

        // If we are setting nulls, then fallback to filesystem.
        Optional<Instant> instant = toInstant(created);
        if (instant.isEmpty()) {
            instant = fileSystemDate(path);
        }
        if (instant.isPresent()) {
            var dateFormat = this.getDateFormat(instant.get());
            Path datePath = outputPath.resolve(nameDir).resolve(dateFormat.year).resolve(dateFormat.month).resolve(dateFormat.day);
            this.createDirs(datePath);
            this.copyFile(path, datePath.resolve(path.getFileName()));
        } else {
            this.createDirs(outputPath.resolve(nameDir));
            this.copyFile(path, outputPath.resolve(nameDir).resolve(path.getFileName()));
            LOGGER.log(Level.WARNING, () -> "File will be placed in " + outputPath.resolve(nameDir) + "  no date source was able to be retrieved: " + path.getFileName());
        }
    }

    private Optional<Instant> fileSystemDate(Path path) {
        BasicFileAttributes attrs = null;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e,
                    () -> "Could not get file attributes: " + path.getFileName() + " due to: " + e.getMessage());
            return Optional.empty();
        }
        Instant created = attrs.creationTime().toInstant();
        Instant modified = attrs.lastModifiedTime().toInstant();
        Instant earliest = created.isBefore(modified) ? created : modified;
        return earliest.getEpochSecond() > 0 ? Optional.of(earliest) : Optional.empty();

    }

    private DateFormat getDateFormat(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());

        String year = zdt.format(YEAR);
        String month = zdt.format(MONTH);
        String day = zdt.format(DAY);
        return new DateFormat(year, month, day);
    }

    private void createDirs(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    private void copyFile(Path source, Path dest) throws IOException {
        Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private record DateFormat(String year, String month, String day) {
    }
}

