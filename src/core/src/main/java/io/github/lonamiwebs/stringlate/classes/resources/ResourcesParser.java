package io.github.lonamiwebs.stringlate.classes.resources;

import net.gsantner.opoc.util.FileUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.lonamiwebs.stringlate.classes.resources.tags.ResPlurals;
import io.github.lonamiwebs.stringlate.classes.resources.tags.ResString;
import io.github.lonamiwebs.stringlate.classes.resources.tags.ResStringArray;
import io.github.lonamiwebs.stringlate.classes.resources.tags.ResTag;
import io.github.lonamiwebs.stringlate.classes.resources.tags.ResType;

// Class used to parse strings.xml files into Resources objects
// Please NOTE that strings with `translatable="false"` will NOT be parsed
// The application doesn't need these to work (as of now, if any use is found, revert this file)
public class ResourcesParser {

    //region Constants

    // We don't use namespaces
    private final static String ns = null;

    private final static String RESOURCES = "resources";

    private final static String ID = "name";
    private final static String QUANTITY = "quantity";
    private final static String INDEX = "index";
    private final static String MODIFIED = "modified";

    // Not every application uses the official "translatable" name
    private final static String[] TRANSLATABLE = {
            "translatable", "translate", "translateable"
    };

    // Nor they use "translatable" instead ignoring missing
    private final static String TOOLS_IGNORE = "tools:ignore";
    private final static String MISSING_TRANSLATION = "MissingTranslation";

    private final static boolean DEFAULT_TRANSLATABLE = true;
    private final static boolean DEFAULT_MODIFIED = false;
    private final static int DEFAULT_INDEX = -1;

    //endregion

    //region Xml -> Resources

    static void loadFromXml(final InputStream in, final Resources resources, final XmlPullParser parser)
            throws XmlPullParserException, IOException {

        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);
            parser.nextTag();
            readResourcesInto(parser, resources);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void readResourcesInto(final XmlPullParser parser, final Resources resources)
            throws XmlPullParserException, IOException {

        parser.require(XmlPullParser.START_TAG, ns, RESOURCES);
        if (MISSING_TRANSLATION.equals(parser.getAttributeValue(null, TOOLS_IGNORE))) {
            // tools:ignore="MissingTranslation"
            // Since missing translations can be ignored, they don't need a translation
            return;
        }

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG)
                continue;

            String name = parser.getName();

            boolean translatable = true;
            for(int i=0; i<parser.getAttributeCount(); i++) {
                for(String tr: TRANSLATABLE) {
                    if (parser.getAttributeName(i).equals(tr) && parser.getAttributeValue(i).equals("false"))
                        translatable = false;
                }
            }

            if (!translatable) {
                skip(parser);
                continue;
            }

            switch (ResType.fromTagName(name)) {
                case STRING:
                    ResTag rt = readResourceString(parser);
                    if (rt != null)
                        resources.loadTag(rt);
                    break;
                case STRING_ARRAY:
                    for (ResTag item : readResourceStringArray(parser))
                        resources.loadTag(item);
                    break;
                case PLURALS:
                    for (ResTag item : readResourcePlurals(parser))
                        resources.loadTag(item);
                    break;
                default:
                    skip(parser);
                    break;
            }
        }
    }

    // Reads a <string name="...">...</string> tag from the xml.
    // This assumes that the .xml has been cleaned (i.e. there are no untranslatable strings)
    private static ResString readResourceString(XmlPullParser parser)
            throws XmlPullParserException, IOException {

        String id, content;
        boolean modified;

        parser.require(XmlPullParser.START_TAG, ns, ResType.STRING.toString());
        if (MISSING_TRANSLATION.equals(parser.getAttributeValue(null, TOOLS_IGNORE))) {
            return null;
        }

        id = parser.getAttributeValue(null, ID);

        // Metadata
        modified = readBooleanAttr(parser, MODIFIED, DEFAULT_MODIFIED);

        // The content must be read last, since it also consumes the tag
        content = ResTag.desanitizeContent(getInnerXml(parser));

        parser.require(XmlPullParser.END_TAG, ns, ResType.STRING.toString());

        if (id == null || content.isEmpty())
            return null;
        else
            return new ResString(ResType.STRING.markID(id), content, modified);
    }

    // Reads a <string-array name="...">...</string-array> tag from the xml.
    private static Iterable<ResStringArray.Item> readResourceStringArray(XmlPullParser parser)
            throws XmlPullParserException, IOException {

        ResStringArray result;
        String id;

        parser.require(XmlPullParser.START_TAG, ns, ResType.STRING_ARRAY.toString());
        if (MISSING_TRANSLATION.equals(parser.getAttributeValue(null, TOOLS_IGNORE))) {
            return null;
        }

        if (!readFirstBooleanAttr(parser, TRANSLATABLE, DEFAULT_TRANSLATABLE)) {
            // We don't care about not-translatable strings
            skipInnerXml(parser);
            parser.require(XmlPullParser.END_TAG, ns, ResType.STRING_ARRAY.toString());
            return new ArrayList<>();
        } else {
            id = parser.getAttributeValue(null, ID);
            result = new ResStringArray(ResType.STRING_ARRAY.markID(id));

            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG)
                    continue;

                String name = parser.getName();
                if (ResType.fromTagName(name) == ResType.ITEM) {
                    parser.require(XmlPullParser.START_TAG, ns, name);
                    boolean modified = readBooleanAttr(parser, MODIFIED, DEFAULT_MODIFIED);
                    int index = readIntAttr(parser, INDEX, DEFAULT_INDEX);

                    String content = ResTag.desanitizeContent(getInnerXml(parser));
                    if (!content.isEmpty())
                        result.addItem(content, modified, index);
                } else {
                    skip(parser);
                }
            }

            return result.expand();
        }
    }

    // Reads a <string-array name="...">...</string-array> tag from the xml.
    private static Iterable<ResPlurals.Item> readResourcePlurals(XmlPullParser parser)
            throws XmlPullParserException, IOException {

        ResPlurals result;
        String id;

        parser.require(XmlPullParser.START_TAG, ns, ResType.PLURALS.toString());
        if (MISSING_TRANSLATION.equals(parser.getAttributeValue(null, TOOLS_IGNORE))) {
            return null;
        }

        if (!readFirstBooleanAttr(parser, TRANSLATABLE, DEFAULT_TRANSLATABLE)) {
            // We don't care about not-translatable strings
            skipInnerXml(parser);
            parser.require(XmlPullParser.END_TAG, ns, ResType.PLURALS.toString());
            return new ArrayList<>();
        } else {
            id = parser.getAttributeValue(null, ID);
            result = new ResPlurals(ResType.PLURALS.markID(id));

            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG)
                    continue;

                String name = parser.getName();
                if (ResType.fromTagName(name) == ResType.ITEM) {
                    parser.require(XmlPullParser.START_TAG, ns, name);
                    String quantity = parser.getAttributeValue(null, QUANTITY);
                    boolean modified = readBooleanAttr(parser, MODIFIED, DEFAULT_MODIFIED);
                    String content = ResTag.desanitizeContent(getInnerXml(parser));
                    if (!content.isEmpty())
                        result.addItem(quantity, content, modified);
                } else {
                    skip(parser);
                }
            }

            return result.expand();
        }
    }

    // Reads a boolean attribute from an xml tag
    private static boolean readBooleanAttr(XmlPullParser parser, String attr, boolean defaultV) {
        String value = parser.getAttributeValue(null, attr);
        if (value == null)
            return defaultV;

        return Boolean.parseBoolean(value);
    }

    private static boolean readFirstBooleanAttr(XmlPullParser parser, String[] attrs, boolean defaultV) {
        for (String attr : attrs) {
            String value = parser.getAttributeValue(null, attr);
            if (value != null) {
                return Boolean.parseBoolean(value);
            }
        }

        return defaultV;
    }

    private static int readIntAttr(XmlPullParser parser, String attr, int defaultV) {
        String value = parser.getAttributeValue(null, attr);
        if (value == null)
            return defaultV;

        return Integer.parseInt(value);
    }

    // Reads the inner xml of a tag before moving to the next one
    // Original source: stackoverflow.com/a/16069754/4759433 by @Maarten
    // TODO This will fail with: &lt;a&gt;text</a>
    private static String getInnerXml(XmlPullParser parser)
            throws XmlPullParserException, IOException {

        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    if (depth > 0) {
                        sb.append("</")
                                .append(parser.getName())
                                .append(">");
                    }
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    StringBuilder attrs = new StringBuilder();
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        attrs.append(parser.getAttributeName(i))
                                .append("=\"")
                                .append(parser.getAttributeValue(i))
                                .append("\" ");
                    }
                    sb.append("<")
                            .append(parser.getName())
                            .append(" ")
                            .append(attrs.toString())
                            .append(">");
                    break;
                default:
                    sb.append(parser.getText());
                    break;
            }
        }
        return sb.toString();
    }

    // Skips the inner XML once inside a tag
    private static void skipInnerXml(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    // Skips a tag in the xml
    private static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG)
            throw new IllegalStateException();

        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    --depth;
                    break;
                case XmlPullParser.START_TAG:
                    ++depth;
                    break;
            }
        }
    }

    //endregion

    //region Resources -> Xml

    static boolean parseToXml(final Resources resources, final OutputStream out, final XmlSerializer serializer) {
        // We need to keep track of the parents which we have done already.
        // This is because we previously expanded the children, but they're
        // wrapped under the same parent (which we cannot duplicate).
        HashSet<String> doneParents = new HashSet<>();
        try {
            serializer.setOutput(out, "UTF-8");
            serializer.startTag(ns, RESOURCES);

            for (ResTag rs : resources) {
                if (!rs.hasContent())
                    continue;

                if (rs instanceof ResString) {
                    parseString(serializer, (ResString) rs);
                } else if (rs instanceof ResStringArray.Item) {
                    ResStringArray parent = ((ResStringArray.Item) rs).getParent();
                    if (!doneParents.contains(parent.getId())) {
                        doneParents.add(parent.getId());
                        parseStringArray(serializer, parent);
                    }
                } else if (rs instanceof ResPlurals.Item) {
                    ResPlurals parent = ((ResPlurals.Item) rs).getParent();
                    if (!doneParents.contains(parent.getId())) {
                        doneParents.add(parent.getId());
                        parsePlurals(serializer, parent);
                    }
                }
            }
            serializer.endTag(ns, RESOURCES);
            serializer.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void parseString(XmlSerializer serializer, ResString string)
            throws IOException {
        serializer.startTag(ns, ResType.STRING.toString());
        serializer.attribute(ns, ID, ResType.resolveID(string.getId()));

        // Only save changes that differ from the default, to save space
        if (string.wasModified() != DEFAULT_MODIFIED)
            serializer.attribute(ns, MODIFIED, Boolean.toString(string.wasModified()));

        serializer.text(ResTag.sanitizeContent(string.getContent()));
        serializer.endTag(ns, ResType.STRING.toString());
    }

    private static void parseStringArray(XmlSerializer serializer, ResStringArray array)
            throws IOException {
        serializer.startTag(ns, ResType.STRING_ARRAY.toString());
        serializer.attribute(ns, ID, ResType.resolveID(array.getId()));

        for (ResStringArray.Item item : array.expand()) {
            serializer.startTag(ns, ResType.ITEM.toString());
            if (item.wasModified() != DEFAULT_MODIFIED)
                serializer.attribute(ns, MODIFIED, Boolean.toString(item.wasModified()));

            // We MUST save the index because the user might have
            // translated first the non-first item from the array. Darn it!
            serializer.attribute(ns, INDEX, Integer.toString(item.getIndex()));
            serializer.text(ResTag.sanitizeContent(item.getContent()));
            serializer.endTag(ns, ResType.ITEM.toString());
        }

        serializer.endTag(ns, ResType.STRING_ARRAY.toString());
    }

    private static void parsePlurals(XmlSerializer serializer, ResPlurals plurals)
            throws IOException {
        serializer.startTag(ns, ResType.PLURALS.toString());
        serializer.attribute(ns, ID, ResType.resolveID(plurals.getId()));

        for (ResPlurals.Item item : plurals.expand()) {
            serializer.startTag(ns, ResType.ITEM.toString());
            serializer.attribute(ns, QUANTITY, item.getQuantity());

            if (item.wasModified() != DEFAULT_MODIFIED)
                serializer.attribute(ns, MODIFIED, Boolean.toString(item.wasModified()));

            serializer.text(ResTag.sanitizeContent(item.getContent()));
            serializer.endTag(ns, ResType.ITEM.toString());
        }

        serializer.endTag(ns, ResType.PLURALS.toString());
    }

    //endregion

    //region Xml -> Xml without untranslatable strings

    public static void cleanXml(File file, Resources resources) {
        try {
            String xml = FileUtils.readTextFile(file);

            // 1. Find dirty tags (those which are untranslatable)
            Queue<DirtyRange> dirtyRanges = new LinkedList<>();

            Matcher mTag = RES_TAG_PATTERN.matcher(xml);
            while (mTag.find()) {
                String id = getAttr(mTag.group(3), ID);
                if (id.isEmpty())
                    continue;

                if (!resources.contains(id)) {
                    // Decrease the range by 1 not to eat up the next character (due to the i++)
                    dirtyRanges.add(new DirtyRange(mTag.start(), mTag.end() - 1));
                }
            }

            // We might want to early terminate if all strings are translatable
            if (dirtyRanges.isEmpty()) {
                // Simply return as the file is unmodified
                return;
            }

            FileOutputStream out = new FileOutputStream(file);

            removeDirtyRanges(xml, dirtyRanges, out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //endregion

    //region Using another file as a template

    //region Applying the template

    // This will match either <string>, <string-array>, <plurals> or <item> from start to end.
    // It will also match the attributes (attribute_a="value" attribute_b="value") and the content.
    private static Pattern RES_TAG_PATTERN = Pattern.compile(
            "((?:\\s*)?)<(string(?:-array)?|plurals|comment)((?:\\s+\\w+\\s*=\\s*\"\\w+\")*)\\s*>([\\s\\S]*?)(</\\s*\\2\\s*>)");

    // This should be matched against the .group(2) from the above pattern
    private static Pattern ATTRIBUTE_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*\"(\\w+)\"");

    private static Pattern COMMENT_PATTERN = Pattern.compile("<!--(.*?)-->");

    private static Pattern RESOURCES_START = Pattern.compile(".*?<resources>");
    private static Pattern RESOURCES_END = Pattern.compile("\\s*</resources>.*");

    //region Actual code

    private static class DirtyRange {
        final int start, end;

        DirtyRange(int s, int e) {
            start = s;
            end = e;
        }
    }

    private static String getAttr(String attrs, String... attrNames) {
        Matcher m = ATTRIBUTE_PATTERN.matcher(attrs);
        while (m.find()) {
            for (String attrName : attrNames) {
                if (m.group(1).equals(attrName)) {
                    return m.group(2);
                }
            }
        }
        return "";
    }

    private static boolean isWhitespace(String string) {
        for (int i = 0; i < string.length(); i++)
            if (!Character.isWhitespace(string.charAt(i)))
                return false;
        return true;
    }

    // If template is not in the same order as the old xml, applyTemplate doesn't find
    // all the old translations anymore. Reorder the translation file before applying
    // the template, according to template order.
    private static String reorderWithTemplate(String templateXml, String oldXml) {
        Matcher mResourcesStart = RESOURCES_START.matcher(oldXml);
        Matcher mResourcesEnd = RESOURCES_END.matcher(oldXml);
        String start = "<resources>";
        String end = "</resources>";
        if (mResourcesStart.find())
            start = mResourcesStart.group();
        if (mResourcesEnd.find())
            end = mResourcesEnd.group();

        HashMap<String, String> idContentMap = new HashMap<>();
        StringBuilder sb = new StringBuilder();

        Matcher mTemplateTag = RES_TAG_PATTERN.matcher(templateXml);
        Matcher mTag = RES_TAG_PATTERN.matcher(oldXml);

        sb.append(start);
        while (mTag.find()) {
            String id = getAttr(mTag.group(3), ID);
            // We keep the complete match to preserve formating
            idContentMap.put(id, mTag.group());
        }
        while (mTemplateTag.find()) {
            String id = getAttr(mTemplateTag.group(3), ID);
            String content = idContentMap.get(id);
            if (content != null)
                sb.append(content);
        }
        sb.append(end);

        return sb.toString();
    }

    // Returns TRUE if the template was applied successfully
    public static boolean applyTemplate(File template, File oldFile, Resources resources, OutputStream out) {
        // Load resources from the template, so we know what to translate.
        Resources defaultResources = Resources.empty();
        final XmlPullParser parser;
        try {
            parser = XmlPullParserFactory.newInstance().newPullParser();
        } catch (XmlPullParserException e) {
            return false;
        }

        try {
            loadFromXml(new FileInputStream(template), defaultResources, parser);
        } catch (XmlPullParserException e) {
            return false;
        } catch (IOException e) {
            return false;
        }

        // If there is nothing to translate, we don't write anything
        if (defaultResources.count() == 0)
            return false;

        String oldXml = "";
        String xml = "";
        try {
            if(oldFile != null)
                oldXml = FileUtils.readTextFile(oldFile);
        } catch (Exception ignored) {
        }
        try {
            xml = FileUtils.readTextFile(template);
        } catch (Exception ignored) {
        }

        Matcher mResourcesStart = RESOURCES_START.matcher(oldXml);
        Matcher mResourcesEnd = RESOURCES_END.matcher(oldXml);
        String start = "<resources>";
        String end = "</resources>";
        if (mResourcesStart.find())
            start = mResourcesStart.group();
        if (mResourcesEnd.find())
            end = mResourcesEnd.group();

        // Non-greedy pattern
        // We keep comments as their own tag, as they may contain commented
        // strings that would be matched and copied over.
        final String stringlateCommentArtifact = "<comment name=\"stringlate:comment\"></comment>";
        String templateXml = xml.replaceAll("<!--.*?-->", stringlateCommentArtifact);
        // Same with translated file
        oldXml = oldXml.replaceAll("<!--.*?-->", stringlateCommentArtifact);

        StringBuilder sb = new StringBuilder();

        oldXml = reorderWithTemplate(templateXml, oldXml);

        Matcher mTemplateTag = RES_TAG_PATTERN.matcher(templateXml);
        Matcher mTag = RES_TAG_PATTERN.matcher(oldXml);

        boolean found;

        do {
            String id = "";
            found = mTag.find();

            if (found) {
                id = getAttr(mTag.group(3), ID);
                if (id.isEmpty())
                    continue;
            }

            boolean cont = true;
            while(cont) {
                boolean matched = mTemplateTag.find();
                if (!matched) {
                    break;
                }

                String templateId = getAttr(mTemplateTag.group(3), ID);
                if (templateId.equals(id))
                    cont = false;

                ResType templateType = ResType.fromTagName(mTemplateTag.group(2));
                templateId = templateType.markID(templateId);
                ResTag templateTag = resources.getTag(templateType.markID(templateId));

                // The next item from the template file did not exist in the
                // old translated file.
                if (templateId.equals("stringlate:comment")) {
                    // We add the artifact and whitespace
                    sb.append(mTemplateTag.group(1));
                    sb.append(stringlateCommentArtifact);
                } else if (resources.contains(templateId)) {
                    if(resources.getTag(templateId).wasModified() || !found) {
                        switch (templateType) {
                            case STRING:
                                sb.append(mTemplateTag.group().
                                        replace(mTemplateTag.group(4), resources.getContent(templateId)));
                                break;
                            case STRING_ARRAY:
                                ResStringArray sa = ((ResStringArray.Item) templateTag).getParent();
                                sb.append("<string-array");
                                sb.append(mTemplateTag.group(3));
                                sb.append(">\n");
                                for (ResStringArray.Item i : sa.expand()) {
                                    sb.append("    <item>");
                                    sb.append(resources.getTag(i.getId()));
                                    sb.append("</item>");
                                }
                                sb.append("</string-array>");
                                break;
                            case PLURALS:
                                ResPlurals p = ((ResPlurals.Item) templateTag).getParent();
                                sb.append("<string-array");
                                sb.append(mTemplateTag.group(3));
                                sb.append(">\n");
                                for (ResPlurals.Item i : p.expand()) {
                                    sb.append("    <item quantity=\"");
                                    sb.append(i.getQuantity());
                                    sb.append("\">");
                                    sb.append(resources.getTag(i.getId()));
                                    sb.append("</item>");
                                }
                                sb.append("</string-array>");
                                break;
                            default:
                                break;
                        }
                    } else {
                        sb.append(mTag.group());
                    }
                }
            }
        } while(found);

        String newXml = sb.toString();

        newXml = start + newXml + end;

        Matcher mComm = COMMENT_PATTERN.matcher(xml);
        while(mComm.find()) {
            // Replace one by one, in order
            newXml = newXml.replace(stringlateCommentArtifact, mComm.group());
        }

        PrintWriter writer = new PrintWriter(out);
        writer.append(newXml);
        writer.close();

        return true;
    }

    //endregion

    //endregion

    //endregion

    //region Private utilities

    // Removes the dirty ranges on string. If a line containing
    // a dirty range is then empty, this line will also be removed.
    // The result will be output to the given output stream.
    private static void removeDirtyRanges(final String string,
                                          final Queue<DirtyRange> dirtyRanges,
                                          final OutputStream output)
            throws IOException {
        int line = 0;
        int lastLine = -1; // To avoid adding the same line twice
        Queue<Integer> dirtyLines = new LinkedList<>();

        // Save result here
        StringBuilder noDirty = new StringBuilder();

        // Get first range and iterate over the characters of the xml
        DirtyRange range = dirtyRanges.poll();
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);

            if (range == null || i < range.start) {
                // Copy this character since it's not part of the dirty tag
                noDirty.append(c);

                // Note how we increment the line iff it was copied,
                // otherwise it was removed and should be excluded
                if (c == '\n') {
                    line++;
                }
            } else {
                // Omit these characters since we're in a dirty tag,
                // and mark the line as dirty iff it wasn't marked before
                if (lastLine != line) {
                    dirtyLines.add(line);
                    lastLine = line;
                }

                // >= not to skip to the next character
                if (i >= range.end) {
                    // We're outside the range now, so pick up the next range
                    range = dirtyRanges.poll();
                }
            }
        }

        // Clean the dirty lines iff they're whitespace only
        String[] lines = noDirty.toString().split("\\n");
        Integer dirtyLine = dirtyLines.poll();

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));
        for (int i = 0; i < lines.length; i++) {
            // If there are no more dirty lines
            // Or we are not at a dirty line yet
            // Or this line is not all whitespace, append it
            if (dirtyLine == null || i != dirtyLine || !isWhitespace(lines[i])) {
                writer.write(lines[i]);
                writer.write('\n');
            } else {
                // Get the next dirty line while ignoring this line too
                dirtyLine = dirtyLines.poll();
            }
        }
        writer.close();
    }

    //endregion
}
