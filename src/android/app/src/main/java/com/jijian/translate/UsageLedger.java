package com.jijian.translate;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class UsageLedger {
    // Flash peak reference prices, CNY per million tokens, checked 2026-10-22.
    static final double INPUT = 2.0, OUTPUT = 8.0;
    private final AtomicFile file;
    private JSONObject data;
    private boolean damaged;
    static final class Ticket {
        final String id, month; final double amount;
        Ticket(String id, String month, double amount) { this.id = id; this.month = month; this.amount = amount; }
    }
    UsageLedger(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "usage.json"));
        try {
            data = file.getBaseFile().exists() ? new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
            if (data.has("months")) {
                JSONObject months = data.getJSONObject("months");
                java.util.Iterator<String> keys = months.keys();
                while (keys.hasNext()) {
                    JSONObject item = months.getJSONObject(keys.next());
                    valid(item.getDouble("spent"));
                    JSONObject pending = item.getJSONObject("pending"); java.util.Iterator<String> ids = pending.keys();
                    while (ids.hasNext()) valid(pending.getDouble(ids.next()));
                }
            } else if (file.getBaseFile().exists()) throw new Exception("Missing ledger");
            else data.put("months", new JSONObject());
        } catch (Exception e) { damaged = true; data = new JSONObject(); }
    }
    private static void valid(double number) throws Exception { if (!Double.isFinite(number) || number < 0) throw new Exception("Invalid ledger"); }
    static String monthNow() { return new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date()); }
    private JSONObject month(String name) throws Exception {
        if (damaged) throw new Exception("用量记录无法读取，已暂停付费请求。请先在平台核对账单，再在设置中重置本机记录。");
        JSONObject months = data.getJSONObject("months");
        if (!months.has(name)) months.put(name, new JSONObject().put("spent", 0).put("pending", new JSONObject()));
        return months.getJSONObject(name);
    }
    synchronized double[] totals() throws Exception {
        JSONObject current = month(monthNow()); JSONObject pending = current.getJSONObject("pending"); double reserved = 0;
        java.util.Iterator<String> ids = pending.keys(); while (ids.hasNext()) reserved += pending.getDouble(ids.next());
        return new double[] {current.getDouble("spent"), reserved, pending.length()};
    }
    synchronized Ticket reserve(double estimate, double budget) throws Exception {
        valid(estimate); valid(budget); double[] total = totals();
        if (total[0] + total[1] + estimate > budget) throw new Exception("已达到本机月预算，请到设置查看或调整。");
        Ticket ticket = new Ticket(UUID.randomUUID().toString(), monthNow(), estimate);
        month(ticket.month).getJSONObject("pending").put(ticket.id, estimate); save(); return ticket;
    }
    synchronized void settle(Ticket ticket, ApiClient.Usage usage, boolean mayCharge) throws Exception {
        if (ticket == null) return; JSONObject current = month(ticket.month); JSONObject pending = current.getJSONObject("pending");
        if (!pending.has(ticket.id)) return;
        if (usage != null) {
            current.put("spent", current.getDouble("spent") + (usage.input * INPUT + usage.output * OUTPUT) / 1_000_000.0);
            pending.remove(ticket.id);
        } else if (!mayCharge) pending.remove(ticket.id);
        save();
    }
    synchronized void reset() throws Exception {
        data = new JSONObject().put("months", new JSONObject()); damaged = false; save();
    }
    private void save() throws Exception {
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(data.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception e) { if (stream != null) file.failWrite(stream); damaged = true; throw new Exception("用量未能保存，已暂停付费请求。"); }
    }
}
