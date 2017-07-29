package com.gmail.collinsmith70.logscanner;

import com.google.common.collect.ImmutableSet;

import com.github.sheigutn.pushbullet.Pushbullet;
import com.github.sheigutn.pushbullet.items.channel.OwnChannel;
import com.github.sheigutn.pushbullet.items.file.UploadFile;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.ByteOrderMark;
import org.apache.commons.io.input.BOMInputStream;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.awt.*;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

public class Main {

  private static final boolean DBG = false;

  private static final Set<String> IGNORED = ImmutableSet.<String>builder()
      .add("Key")
      .add("Gem")
      .add("Essence")
      .build();

  private static final Set<String> DONT_SNAPSHOT = ImmutableSet.<String>builder()
      .add("Key")
      .add("Gem")
      .add("Essence")
      .add("Rune")
      .build();

  @NotNull
  private static final XPath XPATH = XPathFactory.newInstance().newXPath();

  private static final Color[] COLORS = {
      new Color(0xffffff),
      new Color(0xdc143c),//red
      new Color(0x00C408),//set
      new Color(0x4850B8),//magic
      new Color(0x908858),//unique
      new Color(0x000000),
      new Color(0x000000),
      new Color(0x908858),//unique
      new Color(0xFF8000),//crafted
      new Color(0xFFFF00),//rare
      new Color(0xffffff),
  };

  public static void main(String... args) throws Exception {
    System.out.println("ETAL LogScanner");
    if (DBG) System.out.println("Debug mode is ENABLED");

    Options options = new Options();
    options.addOption(Option.builder("d")
        .desc("Deletes and saved preferences")
        .longOpt("deletePrefs")
        .build());
    options.addOption(Option.builder("h")
        .desc("Displays this message")
        .longOpt("help")
        .build());
    options.addOption(Option.builder("k")
        .desc("Pushbullet API Key")
        .hasArg()
        .longOpt("apiKey")
        .build());
    options.addOption(Option.builder("c")
        .desc("Pushbullet Channel")
        .hasArg()
        .longOpt("channel")
        .build());
    options.addOption(Option.builder("e")
        .desc("ETAL Home Folder (contains D2Etal folder)")
        .hasArg()
        .longOpt("etal")
        .build());

    CommandLineParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);

    if (cmd.hasOption("h")) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("logscanner", options);
      System.exit(0);
      return;
    }

    Preferences prefs = Preferences.userNodeForPackage(Main.class);
    if (cmd.hasOption("d")) {
      prefs.clear();
    }

    String apiToken = cmd.hasOption('k')
        ? cmd.getOptionValue('k')
        : prefs.get("apiToken", null);
    if (apiToken == null) {
      System.out.println("Pushbullet API key was not specified or could not be located as a saved preference.");
      System.exit(0);
      return;
    } else {
      prefs.put("apiToken", apiToken);
    }

    String channelName = cmd.hasOption('c')
        ? cmd.getOptionValue('c')
        : prefs.get("channelName", null);
    if (channelName == null) {
      System.out.println("Pushbullet channel was not specified or could not be located as a saved preference.");
      System.exit(0);
      return;
    } else {
      prefs.put("channelName", channelName);
    }

    String etal = cmd.hasOption('e')
        ? cmd.getOptionValue('e')
        : prefs.get("etal", null);
    if (etal == null) {
      System.out.println("ETAL home folder path was not specified or could not be located as a saved preference.");
      System.exit(0);
      return;
    } else {
      prefs.put("etal", etal);
    }

    System.out.println("Connecting to Pushbullet service...");
    final Pushbullet PUSHBULLET = new Pushbullet(apiToken);

    System.out.println("Connecting to " + channelName + "...");
    final OwnChannel CHANNEL = PUSHBULLET.getOwnChannel(channelName);

    final Path ETAL = Paths.get(etal);
    System.out.println("ETAL: " + ETAL);
    if (!Files.isDirectory(ETAL)) {
      throw new RuntimeException("Etal option does not reference a valid folder!");
    }

    Item lastItem = null;
    final Path LOGS = ETAL.resolve("D2Etal/scripts/logs/Item Log");
    try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
      LOGS.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
      System.out.println("Ready.");
      for (;;) {
        WatchEvent.Kind kind = null;
        WatchKey key = watcher.take();
        for (WatchEvent event : key.pollEvents()) {
          kind = event.kind();
          if (kind == StandardWatchEventKinds.OVERFLOW) {
            continue;
          } else {
            Path context = (Path) event.context();
            if (!context.toString().endsWith("_itemlog.xml")) {
              continue;
            }

            if (DBG) System.out.println("detected: " + kind + " at " + context);

            Path log = LOGS.resolve(context);
            try {
              Item item = null;
              try {
                item = parseItem(log);
              } catch (FileSystemException inUse) {
                if (DBG) System.out.println("File in use... waiting");
                Thread.sleep(1000);
                if (DBG) System.out.println("Finished waiting.");
                item = parseItem(log);
              }

              if (IGNORED.contains(item.typedesc)) {
                if (DBG) System.out.println("Item ignored.");
                continue;
              }

              if (lastItem != null
                  && lastItem.name.equals(item.name)
                  && lastItem.location.equals(item.location)) {
                if (DBG) System.out.println("Item equal to lastItem.");
                continue;
              }

              lastItem = item;
              String body = String.format("%s found %s", item.character, item);
              if (DONT_SNAPSHOT.contains(item.typedesc)) {
                CHANNEL.pushNote(null, body);
                System.out.printf("Pushing %s::%s%n", item.character, item.name);
              } else {
                RenderedImage snapshot = item.snapshot();
                Path tmp = Paths.get(System.getProperty("java.io.tmpdir"), item.name + ".png");
                File asFile = tmp.toFile();
                boolean success = ImageIO.write(snapshot, "png", asFile);
                if (!success) {
                  throw new RuntimeException("Failed to write image for " + item.name);
                }

                UploadFile uploadFile = PUSHBULLET.uploadFile(asFile);
                CHANNEL.pushFile(body, uploadFile);
                System.out.printf("Pushing %s::%s [%s]%n", item.character, item.name,
                    uploadFile.getFileUrl());
                boolean deleted = asFile.delete();
                if (!deleted && DBG) {
                  System.out.println("Failed to delete " + tmp);
                }
              }
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        }

        if (!key.reset()) {
          break;
        }
      }
    }
  }

  private static final DocumentBuilderFactory DOC_BUILDER_FACTORY
      = DocumentBuilderFactory.newInstance();

  @NotNull
  private static Item parseItem(@NotNull Path p) throws Exception {
    DocumentBuilder builder = DOC_BUILDER_FACTORY.newDocumentBuilder();
    BOMInputStream in = new BOMInputStream(Files.newInputStream(p),
        ByteOrderMark.UTF_16LE, ByteOrderMark.UTF_8);
    InputSource inputSource = new InputSource(new InputStreamReader(in, "UTF-16LE"));
    Document document = builder.parse(inputSource);

    Node result = (Node) XPATH.evaluate("/itemlog/item[last()]", document, XPathConstants.NODE);
    return Item.parse(result);
  }

  //(3 = White, 4 = Magic, 5 = Sets, 6 = Rares, 7 = Uniques, 8 = Crafted
  private static final int map(int quality) {
    switch (quality) {
      case 1:
      case 2:
      case 3:
        return 0;
      case 4:
        return 3;
      case 5:
        return 2;
      case 6:
        return 9;
      case 7:
        return 4;
      case 8:
        return 8;
      default:
        return 10;
    }
  }

  private static boolean startsWithColor(String text) {
    return text.length() >= 3
        && text.charAt(0) == '\u00FF'
        && text.charAt(1) == 'c';
  }

  private static String stripColor(String text) {
    while (startsWithColor(text)) {
      text = text.substring(3);
    }

    return text;
  }

  private static Color parseColor(String text) {
    Color c = COLORS[10];
    while (startsWithColor(text)) {
      switch (text.charAt(2) - '0') {
        case 0: c = COLORS[0]; break;
        case 1: c = COLORS[1]; break;
        case 2: c = COLORS[2]; break;
        case 3: c = COLORS[3]; break;
        case 4: c = COLORS[4]; break;
        case 5: c = COLORS[5]; break;
        case 6: c = COLORS[6]; break;
        case 7: c = COLORS[7]; break;
        case 8: c = COLORS[8]; break;
        case 9: c = COLORS[9]; break;
        default: throw new AssertionError("Invalid Option: " + (text.charAt(2) - '0'));
      }

      text = text.substring(3);
    }

    return c;
  }

  private static final class Item {
    final long id;
    final String character;
    final String name;
    final int ilvl;
    final int type;
    final int quality;
    final int mode;
    final String typedesc;
    final String location;
    final boolean ethereal;
    final String desc;

    static Item parse(Node node) {
      return new Item(node);
    }

    private Item(Node node) {
      NamedNodeMap attrs = node.getAttributes();
      id = Long.parseLong(attrs.getNamedItem("id").getTextContent());
      character = attrs.getNamedItem("char").getTextContent();
      name = attrs.getNamedItem("name").getTextContent();
      ilvl = Integer.parseInt(attrs.getNamedItem("ilvl").getTextContent());
      type = Integer.parseInt(attrs.getNamedItem("type").getTextContent());
      quality = Integer.parseInt(attrs.getNamedItem("quality").getTextContent());
      mode = Integer.parseInt(attrs.getNamedItem("mode").getTextContent());
      typedesc = attrs.getNamedItem("typedesc").getTextContent();
      location = attrs.getNamedItem("location").getTextContent();
      ethereal = Boolean.parseBoolean(attrs.getNamedItem("ethereal").getTextContent());
      desc = node.getTextContent();
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      if (ethereal) {
        builder.append("ethereal");
        builder.append(' ');
      }

      builder.append('[');
      builder.append(name);
      builder.append(']');
      return builder.toString();
    }

    @NotNull
    public RenderedImage snapshot() throws Exception {
      BufferedImage img = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2d = img.createGraphics();
      g2d.setFont(new Font("arial", Font.PLAIN, 32));
      FontMetrics metrics = g2d.getFontMetrics();

      int maxWidth = Integer.MIN_VALUE;
      maxWidth = Math.max(maxWidth, metrics.stringWidth(name));

      //if (DBG) System.out.println(desc);
      for (String s : desc.split("\\|")) {
        s = stripColor(s);
        //System.out.println(s);
        maxWidth = Math.max(maxWidth, metrics.stringWidth(s));
      }

      maxWidth = Math.max(maxWidth, metrics.stringWidth("Area: " + location));

      float mid = maxWidth / 2;

      float y = 0, width;
      LineMetrics lineMetrics;
      try {
        if (!typedesc.equalsIgnoreCase("Essence")
            && !typedesc.equalsIgnoreCase("Key")) {
          lineMetrics = metrics.getLineMetrics(name, g2d);
          y += lineMetrics.getHeight();
          width = metrics.stringWidth(name);

          g2d.setColor(COLORS[map(quality)]);
          g2d.drawString(name, mid - (width / 2), y);
        }

        String[] descs = desc.split("\\|");
        for (String s : descs) {
          String part = stripColor(s);
          lineMetrics = metrics.getLineMetrics(part, g2d);
          width = metrics.stringWidth(part);
          y += lineMetrics.getHeight();
          g2d.setColor(parseColor(s));
          g2d.drawString(part, mid - (width / 2), y);
        }

        String str = "Area: " + location;
        width = metrics.stringWidth(str);
        lineMetrics = metrics.getLineMetrics(str, g2d);
        y += lineMetrics.getHeight();

        g2d.setColor(COLORS[2]);
        g2d.drawString("Area: " + location, mid - (width / 2), y);

        img = img.getSubimage(0, 0, maxWidth, (int) y + metrics.getDescent());
      } finally {
        g2d.dispose();
      }

      return img;
    }
  }
}
