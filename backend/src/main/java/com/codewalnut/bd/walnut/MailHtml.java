package com.codewalnut.bd.walnut;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The brief's Markdown (headings, bullets, numbered lists, links, bold) as simple email HTML. */
final class MailHtml {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private MailHtml() {}

    static String of(String markdown, String baseUrl) {
        StringBuilder out = new StringBuilder("<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;line-height:1.5;color:#1d2329\">");
        String list = null;
        for (String raw : markdown.split("\n")) {
            String line = raw.strip();
            String kind = line.startsWith("- ") ? "ul" : line.matches("\\d+\\.\\s.*") ? "ol" : null;
            if (list != null && !list.equals(kind)) {
                out.append("</").append(list).append('>');
                list = null;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("## ")) {
                out.append("<h3 style=\"margin:18px 0 6px;font-size:15px\">").append(inline(line.substring(3), baseUrl)).append("</h3>");
            } else if (kind != null) {
                if (list == null) {
                    out.append('<').append(kind).append(" style=\"margin:0;padding-left:20px\">");
                    list = kind;
                }
                out.append("<li>").append(inline(line.replaceFirst("^(- |\\d+\\.\\s)", ""), baseUrl)).append("</li>");
            } else {
                out.append("<p style=\"margin:0 0 10px\">").append(inline(line, baseUrl)).append("</p>");
            }
        }
        if (list != null) {
            out.append("</").append(list).append('>');
        }
        return out.append("</div>").toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String inline(String text, String baseUrl) {
        String escaped = escape(text);
        Matcher m = LINK.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String href = m.group(2);
            if (href.startsWith("/")) {
                href = baseUrl + href;
            } else if (!href.startsWith("https://")) {
                href = null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(href == null ? m.group(1)
                : "<a href=\"" + href + "\" style=\"color:#5b3a29\">" + m.group(1) + "</a>"));
        }
        m.appendTail(sb);
        return BOLD.matcher(sb.toString()).replaceAll("<strong>$1</strong>");
    }
}
