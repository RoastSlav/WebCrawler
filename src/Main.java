import org.apache.commons.cli.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class Main {
    static final String PROGRAM_NAME = "WebCrawler";
    private static final Set<String> PROCESSED_IMAGES = new HashSet<>();
    private static final Set<String> PROCESSED_LINKS = new HashSet<>();
    private static final Queue<String> LINKS_TO_PROCESS = new LinkedList<>();
    private static final HelpFormatter FORMATTER = new HelpFormatter();
    private static final Set<String> FORMATS_TO_DOWNLOAD = new HashSet<>();
    private static final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    private static CommandLine cmd = null;

    public static void main(String[] args) throws Exception {
        Options options = intializeOptions();
        CommandLineParser cmdParser = new DefaultParser();
        try {
            cmd = cmdParser.parse(options, args);
        } catch (ParseException e) {
            FORMATTER.printHelp(PROGRAM_NAME, options, true);
            return;
        }

        if (cmd.hasOption("help")) {
            FORMATTER.printHelp(PROGRAM_NAME, options, true);
            return;
        }

        String[] baseUrl = cmd.getOptionValues("url");
        LINKS_TO_PROCESS.addAll(List.of(baseUrl));
        for (String url : baseUrl)
            processLinks(url);

        String[] formats = cmd.getOptionValues("iFormat");
        FORMATS_TO_DOWNLOAD.addAll(List.of(formats));
    }

    private static Options intializeOptions() {
        Options options = new Options();
        Option url = Option.builder("url").argName("URL").hasArgs().valueSeparator(',').required().desc("The url to scrape").build();
        options.addOption(url);
        Option outputDir = Option.builder("oDir").argName("Output directory").hasArg().desc("The directory where the images will be downloaded").build();
        options.addOption(outputDir);
        Option imageFormat = Option.builder("iFormat").argName("Image format").hasArgs().valueSeparator(',').desc("The image format to be downloaded").build();
        options.addOption(imageFormat);
        Option userAgent = Option.builder("uAgent").argName("User agent").hasArg().desc("User agent to be sent to the server").build();
        options.addOption(userAgent);
        return options;
    }

    private static HttpRequest buildRequest(String link) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().GET().uri(URI.create(link));

        String userAgent = cmd.getOptionValue("uAgent");
        if (userAgent != null)
            requestBuilder.header("User-Agent", userAgent);

        return requestBuilder.build();
    }

    private static void processLinks(String baseUrl) throws Exception {
        while (!LINKS_TO_PROCESS.isEmpty()) {
            String link = LINKS_TO_PROCESS.remove();
            if (PROCESSED_LINKS.contains(link))
                continue;

            System.out.println("Currently processing page: " + link);
            HttpRequest request = buildRequest(link);
            Document document = getDocument(request);
            getImagesFromDoc(document);
            PROCESSED_LINKS.add(link);
            LINKS_TO_PROCESS.addAll(List.of(getFollowLinks(document, baseUrl)));
        }
    }

    private static Document getDocument(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return Jsoup.parse(response.body(), response.uri().toString());
    }

    private static void getImagesFromDoc(Document doc) throws Exception {
        Elements images = doc.getElementsByTag("img");
        String saveDirectory = cmd.getOptionValue("oDir");
        if (saveDirectory == null)
            saveDirectory = System.getProperty("user.dir");

        for (Element image : images) {
            String src = image.attr("abs:src");
            if (PROCESSED_IMAGES.contains(src))
                continue;
            PROCESSED_IMAGES.add(src);

            downloadImage(src, saveDirectory);
        }
    }

    private static void downloadImage(String src, String saveDirectory) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(src);
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        Optional<String> contentTypeOptional = response.headers().firstValue("content-type");
        String fileName = getFileName(src, contentTypeOptional);
        if (fileName == null)
            return;

        File file = new File(saveDirectory, fileName);
        try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
            fileOutputStream.write(response.body());
        } catch (FileNotFoundException e) {
            System.out.println("Invalid save path: " + file.getPath());
        } catch (Exception e) {
            System.out.println("Couldn't download image: " + file.getName());
        }
    }

    private static String getFileName(String src, Optional<String> contentTypeOptional) {
        String fileExtension = null;
        if (contentTypeOptional.isPresent()) {
            String s = contentTypeOptional.get();
            int slashIndex = s.lastIndexOf('/') + 1;
            int endIndex = s.lastIndexOf('+');
            if (endIndex == -1)
                endIndex = s.length();

            fileExtension = s.substring(slashIndex, endIndex);
        }

        int fileNameStartIndex = src.lastIndexOf('/') + 1;
        int fileNameEndIndex = src.lastIndexOf('.');
        String fileName = src.substring(fileNameStartIndex, fileNameEndIndex);
        if (fileExtension != null && !FORMATS_TO_DOWNLOAD.contains(fileExtension))
            return null;

        return fileName + "." + fileExtension;
    }

    private static String[] getFollowLinks(Document doc, String pageUrl) {
        ArrayList<String> followLinks = new ArrayList<>();
        Elements links = doc.getElementsByTag("a");
        for (Element link : links) {
            String href = link.attr("abs:href");
            URL linkUrl;
            try {
                linkUrl = new URL(href);
            } catch (Exception e) {
                continue;
            }
            String linkUrlHost = linkUrl.toExternalForm();
            if (linkUrlHost.contains(pageUrl))
                followLinks.add(href);
        }
        return followLinks.toArray(String[]::new);
    }
}