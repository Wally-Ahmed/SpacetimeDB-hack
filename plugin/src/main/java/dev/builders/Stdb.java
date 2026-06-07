package dev.builders;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The entire SpacetimeDB connection — no SDK, just the HTTP API.
 *   call(reducer, args...) -> POST /v1/database/{db}/call/{reducer}   (fire-and-forget)
 *   sql(query)            -> POST /v1/database/{db}/sql               (blocking; call off-thread)
 * Anonymous access works because all our tables are public and the server is local.
 */
public class Stdb {
    private final Plugin plugin;
    private final String base;
    private final String db;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public Stdb(Plugin plugin, String base, String db) {
        this.plugin = plugin;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.db = db;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Fire-and-forget reducer call. Non-blocking; safe to call from the main thread. */
    public void call(String reducer, Object... args) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/database/" + db + "/call/" + reducer))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJsonArray(args)))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 300) {
                        plugin.getLogger().warning("reducer " + reducer + " -> HTTP " + resp.statusCode() + ": " + trim(resp.body()));
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("reducer " + reducer + " failed: " + ex.getMessage());
                    return null;
                });
    }

    /** Blocking SQL query. Returns rows as (column name -> value). Call OFF the main thread. */
    public List<Map<String, JsonElement>> sql(String query) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/database/" + db + "/sql"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(query))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                plugin.getLogger().warning("sql -> HTTP " + resp.statusCode() + ": " + trim(resp.body()));
                return Collections.emptyList();
            }
            return parseSats(resp.body());
        } catch (Exception e) {
            plugin.getLogger().warning("sql failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Parse SpacetimeDB SATS-JSON: [{ "schema": { "elements": [{name:{some}}] }, "rows": [[...]] }]. */
    private List<Map<String, JsonElement>> parseSats(String body) {
        List<Map<String, JsonElement>> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return out;
        JsonObject res = root.getAsJsonArray().get(0).getAsJsonObject();
        JsonArray elements = res.getAsJsonObject("schema").getAsJsonArray("elements");
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            JsonObject nameObj = elements.get(i).getAsJsonObject().getAsJsonObject("name");
            if (nameObj != null && nameObj.has("some")) cols.add(nameObj.get("some").getAsString());
            else cols.add("col" + i);
        }
        JsonArray rows = res.getAsJsonArray("rows");
        for (JsonElement rEl : rows) {
            JsonArray r = rEl.getAsJsonArray();
            Map<String, JsonElement> row = new HashMap<>();
            for (int i = 0; i < cols.size() && i < r.size(); i++) row.put(cols.get(i), r.get(i));
            out.add(row);
        }
        return out;
    }

    private String toJsonArray(Object... args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            Object a = args[i];
            if (a == null) sb.append("null");
            else if (a instanceof Number || a instanceof Boolean) sb.append(a.toString());
            else sb.append('"').append(escape(a.toString())).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String trim(String s) {
        return s == null ? "" : (s.length() > 180 ? s.substring(0, 180) : s);
    }
}
