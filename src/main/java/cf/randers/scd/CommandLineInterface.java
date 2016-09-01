/*
 * SoundCloudDownloader
 * Copyright (C) 2015 Ruben Anders
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package cf.randers.scd;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import gson.obj.Album;
import gson.obj.Track;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.StandardArtwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CommandLineInterface
{

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandLineInterface.class);
    private static final String outputformat = "%d";

    public static void main(String[] args)
    {
        CommandLineInterface commandLineInterface = new CommandLineInterface();
        JCommander jCommander = new JCommander(commandLineInterface, args);
        jCommander.setProgramName("SoundCloudDownloader");
        if (commandLineInterface.help)
            jCommander.usage();
        commandLineInterface.run();
    }

    private void run()
    {
        if (params == null)
            return;
        LOGGER.info("Making temp dir...");
        File tmpDir = new File("tmp/");
        File outDir = new File(outputDirectory);
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        BlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(params.size());
        maximumConcurrentConnections = Math.min(params.size(), maximumConcurrentConnections > params.size() ? params.size() : maximumConcurrentConnections);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(maximumConcurrentConnections, maximumConcurrentConnections, 0, TimeUnit.NANOSECONDS, tasks);
        LOGGER.info("Starting to execute " + params.size() + " thread(s)...");
        for (String param : params)
        {
            executor.execute(() -> {
                LOGGER.info("Started thread for " + param);
                Map json;
                byte[] artworkBytes = new byte[0];
                List<Track> toProcess = new ArrayList<>();
                LOGGER.info("Resolving and querying track info...");
                try (CloseableHttpClient client = HttpClients.createDefault();
                     CloseableHttpResponse response = client.execute(
                             new HttpGet(new URIBuilder()
                                                 .setScheme("https")
                                                 .setHost("api.soundcloud.com")
                                                 .setPath("/resolve")
                                                 .addParameter("url", param)
                                                 .addParameter("client_id", clientID)
                                                 .build()));
                     InputStreamReader inputStreamReader = new InputStreamReader(response.getEntity().getContent()))
                {

                    final int bufferSize = 1024;
                    final char[] buffer = new char[bufferSize];
                    final StringBuilder out = new StringBuilder();
                    for (; ; )
                    {
                        int rsz = inputStreamReader.read(buffer, 0, buffer.length);
                        if (rsz < 0)
                            break;
                        out.append(buffer, 0, rsz);
                    }
                    String rawJson = out.toString();
                    Album a = new Gson().fromJson(rawJson, Album.class);

                    if (a.getTrackCount() == null)
                    {
                        Track tr = new Gson().fromJson(rawJson, Track.class);
                        toProcess.add(tr);
                    }
                    toProcess.addAll(a.getTracks());
                    EntityUtils.consumeQuietly(response.getEntity());
                } catch (Exception e)
                {
                    e.printStackTrace();
                    return;
                }
                for (Track track : toProcess)
                {
                    System.out.println(track.getId());
                    System.out.println(track.getTitle());
                }
                for (Track track : toProcess)
                {
                    LOGGER.info("Downloading mp3 to file...");
                    File tmpFile = new File("tmp/" + String.format("%d", track.getId()) + ".mp3");

                    try (CloseableHttpClient client = HttpClients.createDefault();
                         CloseableHttpResponse response = client.execute(new HttpGet(track.getStreamUrl() + "?client_id=" + clientID)))
                    {
                        IOUtils.copy(response.getEntity().getContent(), new FileOutputStream(tmpFile));
                        EntityUtils.consumeQuietly(response.getEntity());
                    } catch (Exception e)
                    {
                        e.printStackTrace();
                        return;
                    }

                    boolean hasArtwork = track.getArtworkUrl() != null;

                    if (hasArtwork)
                    {
                        LOGGER.info("Downloading artwork jpg into memory...");
                        try (CloseableHttpClient client = HttpClients.createDefault();
                             CloseableHttpResponse response = client.execute(
                                     new HttpGet(track.getArtworkUrl().replace("-large.jpg", "-t500x500.jpg") + "?client_id=" + clientID)))
                        {
                            artworkBytes = IOUtils.toByteArray(response.getEntity().getContent());
                            EntityUtils.consumeQuietly(response.getEntity());
                        } catch (Exception e)
                        {
                            e.printStackTrace();
                            return;
                        }
                    }

                    try
                    {
                        LOGGER.info("Reading temp file into AudioFile object...");
                        // Read audio file from tmp directory
                        AudioFile audioFile = AudioFileIO.read(tmpFile);

                        // Set Artwork
                        Tag tag = audioFile.getTagAndConvertOrCreateAndSetDefault();
                        if (hasArtwork)
                        {
                            StandardArtwork artwork = new StandardArtwork();
                            artwork.setBinaryData(artworkBytes);
                            artwork.setImageFromData();
                            tag.addField(artwork);
                        }
                        tag.addField(FieldKey.TITLE, track.getTitle());
                        tag.addField(FieldKey.ARTIST, track.getUser().getUsername());
                        LOGGER.info("Saving audio file...");
                        System.out.println(outDir.getAbsolutePath() + "/" + String.format(outputformat, track.getId()));
                        new AudioFileIO().writeFile(audioFile, outDir.getAbsolutePath() + "/" + String.format(outputformat, track.getId()));
                        tmpFile.deleteOnExit();
                    } catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }

                File[] listFiles = outDir.listFiles();
                if (listFiles == null)
                {
                    return;
                }
                for (File file : listFiles)
                {
                    file.delete();
                }
            });
        }
        executor.shutdown();
    }

    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    @Parameter(description = "List of SoundCloud links to process")
    private List<String> params;

    @Parameter(names = {"--apitoken", "-A"}, description = "API token to use")
    private String clientID = "d53fca48096e7441a6054f6cde29a2b5";

    @Parameter(names = {"--outdirectory", "-o"}, description = "directory to output the files")
    private String outputDirectory = "H:/music/soundcloud/";

    @Parameter(names = {"--connections", "-C"}, description = "Maximum amount of songs to be processed concurrently.")
    private int maximumConcurrentConnections = 4;

    @Parameter(names = {"--help", "-H", "-?"}, description = "Display help.")
    private boolean help = false;
}
