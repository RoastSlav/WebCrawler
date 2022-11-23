import org.apache.commons.cli.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.Pattern;

public class Main {
    static final String PROGRAM_NAME = "WebCrawler";
    private static final Set<String> PROCESSED_IMAGES = new HashSet<>();
    private static final Set<String> PROCESSED_LINKS = new HashSet<>();
    private static final Queue<String> LINKS_TO_PROCESS = new LinkedList<>();
    private static final HelpFormatter FORMATTER = new HelpFormatter();
    private static CommandLine cmd = null;
    private static final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

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

        String baseUrl = cmd.getOptionValue("url");
        LINKS_TO_PROCESS.add(baseUrl);
        processLinks(baseUrl);
    }

    private static Options intializeOptions() {
        Options options = new Options();
        Option url = Option.builder("url").argName("URL").hasArg().required().desc("The url to scrape").build();
        options.addOption(url);
        Option outputDir = Option.builder("oDir").argName("Output directory").hasArg().desc("The directory where the images will be downloaded").build();
        options.addOption(outputDir);
        Option imageFormat = Option.builder("iFormat").argName("Image format").hasArg().desc("The image format to be downloaded").build();
        options.addOption(imageFormat);
        Option userAgent = Option.builder("uAgent").argName("User agent").hasArg().desc("User agent to be sent to the server").build();
        options.addOption(userAgent);
        return options;
    }

    private static HttpRequest buildRequest(String link) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().GET().uri(URI.create(link));

        String userAgent = cmd.getOptionValue("user-agent");
        if (userAgent != null)
            requestBuilder.header("User-Agent", userAgent);

        return requestBuilder.build();
    }

    private static void processLinks(String baseUrl) throws Exception {
        while (!LINKS_TO_PROCESS.isEmpty()) {
            String link = LINKS_TO_PROCESS.remove();
            if (PROCESSED_LINKS.contains(link))
                continue;

            HttpRequest request = buildRequest(link);

            System.out.println("Currently processing page: " + link);
            Document document = getDocument(request);
            getPicturesFromDoc(document);
            PROCESSED_LINKS.add(link);
            LINKS_TO_PROCESS.addAll(List.of(getFollowLinks(document, baseUrl)));
        }
    }

    private static Document getDocument(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return Jsoup.parse(response.body());
    }

    //TODO: Use httpClient
    //TODO: image extension, get it from content-type header

    private static void getPicturesFromDoc(Document doc) {
        Elements images = doc.getElementsByTag("img");
        String saveDirectory = cmd.getOptionValue("output-dir");
        if (saveDirectory == null)
            saveDirectory = System.getProperty("user.dir");
        String formatToSave = cmd.getOptionValue("image-format");

        for (Element image : images) {
            String src = image.attr("abs:src");
            if (PROCESSED_IMAGES.contains(src))
                continue;
            PROCESSED_IMAGES.add(src);

            int fileNameStartIndex = src.lastIndexOf('/') + 1;
            int questionMarkLastIndex = src.lastIndexOf('?');
            String fileName = questionMarkLastIndex > fileNameStartIndex ?
                    src.substring(fileNameStartIndex, questionMarkLastIndex) :
                    src.substring(fileNameStartIndex);

            if (formatToSave != null) {
                int i = fileName.lastIndexOf('.');
                if (!fileName.substring(i + 1).equals(formatToSave))
                    continue;
            }

            try (InputStream in = new URL(src).openStream();
                 FileOutputStream fileOutputStream = new FileOutputStream(saveDirectory + fileName)) {
                byte dataBuffer[] = new byte[2048];
                for (int bytesRead; (bytesRead = in.read(dataBuffer, 0, 2048)) != -1; ) {
                    fileOutputStream.write(dataBuffer, 0, bytesRead);
                }
            } catch (Exception e) {
                System.out.println("Couldn't download image: " + fileName);
            }
        }
    }

    private static String[] getFollowLinks(Document doc, String pageUrl) throws MalformedURLException {
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