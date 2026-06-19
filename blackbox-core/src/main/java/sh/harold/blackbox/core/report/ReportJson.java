package sh.harold.blackbox.core.report;

import java.io.IOException;

import sh.harold.blackbox.core.json.JsonWriter;

final class ReportJson {

    private ReportJson() {
    }

    static void writeNullable(JsonWriter json, String name, Long value) throws IOException {
        json.name(name);
        if (value == null) {
            json.nullValue();
        } else {
            json.value(value);
        }
    }

    static void writeNullableDouble(JsonWriter json, String name, Double value) throws IOException {
        json.name(name);
        if (value == null) {
            json.nullValue();
        } else {
            json.value(round2(value));
        }
    }

    static void writeDoubleSeries(JsonWriter json, String name, double[] series) throws IOException {
        json.name(name);
        if (series == null) {
            json.nullValue();
            return;
        }
        json.beginArray();
        for (double v : series) {
            json.value(round2(v));
        }
        json.endArray();
    }

    static void writeLongSeries(JsonWriter json, String name, long[] series) throws IOException {
        json.name(name);
        if (series == null) {
            json.nullValue();
            return;
        }
        json.beginArray();
        for (long v : series) {
            json.value(v);
        }
        json.endArray();
    }

    static double avg(double[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    static double max(double[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        double m = values[0];
        for (double v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    static double min(double[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        double m = values[0];
        for (double v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    static double round2(double v) {
        double r = Math.round(v * 100.0) / 100.0;
        return Double.isFinite(r) ? r : 0;
    }
}
