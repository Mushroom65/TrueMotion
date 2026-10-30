package app.truemotion.vector;
import java.io.*;
/** Allocation guards depend on the device rather than a fixed media duration. */
final class WorkBudget {
    static void memory(long bytes)throws IOException {
        Runtime r=Runtime.getRuntime();long available=r.maxMemory()-(r.totalMemory()-r.freeMemory());
        if(bytes<0||bytes>available/2)throw new IOException(L10n.t("Недостаточно памяти для анализа. Разделите источник на фрагменты."));
    }
    static void disk(File dir,long bytes)throws IOException {
        if(bytes<0||bytes>Math.max(0,dir.getUsableSpace()-64L*1024*1024))throw new IOException(L10n.t("Недостаточно места для временных файлов обработки."));
    }
}
