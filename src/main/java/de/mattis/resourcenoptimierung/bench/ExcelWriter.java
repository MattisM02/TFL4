package de.mattis.resourcenoptimierung.bench;

import org.apache.poi.xssf.usermodel.*;

import java.io.FileOutputStream;

public class ExcelWriter {

    private final XSSFWorkbook workbook = new XSSFWorkbook();

    public void write(String file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            workbook.write(out);
        }
    }

    public XSSFSheet runsSheet() {
        return workbook.createSheet("Runs");
    }

    public XSSFSheet latencySheet() {
        return workbook.createSheet("Latency");
    }

    public XSSFSheet dockerStatsSheet() {
        return workbook.createSheet("DockerStats");
    }
}
