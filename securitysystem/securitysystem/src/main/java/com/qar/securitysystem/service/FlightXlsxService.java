package com.qar.securitysystem.service;

import com.qar.securitysystem.dto.FlightXlsxFileResponse;
import com.qar.securitysystem.dto.FlightXlsxPreviewResponse;
import com.qar.securitysystem.model.FileRecordEntity;
import com.qar.securitysystem.repo.FileRecordRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FlightXlsxService {
    private final FileRecordRepository fileRecordRepository;
    private final FileService fileService;
    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    public FlightXlsxService(FileRecordRepository fileRecordRepository, FileService fileService) {
        this.fileRecordRepository = fileRecordRepository;
        this.fileService = fileService;
    }

    public List<FlightXlsxFileResponse> listXlsxFiles() {
        List<FileRecordEntity> all = fileRecordRepository.findAllByOrderByCreatedAtDesc();
        List<FlightXlsxFileResponse> out = new ArrayList<>();
        for (FileRecordEntity r : all) {
            if (!isXlsx(r)) {
                continue;
            }
            FlightXlsxFileResponse f = new FlightXlsxFileResponse();
            f.setId(r.getId());
            f.setOriginalName(r.getOriginalName());
            f.setContentType(r.getContentType());
            f.setSizeBytes(r.getSizeBytes());
            f.setCreatedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            out.add(f);
        }
        return out;
    }

    public FlightXlsxPreviewResponse previewFile(String fileId, int maxRowsPerSheet) {
        FileRecordEntity r = fileRecordRepository.findById(fileId).orElse(null);
        if (r == null) {
            throw new IllegalArgumentException("not_found");
        }
        if (!isXlsx(r)) {
            throw new IllegalArgumentException("not_xlsx");
        }
        if (r.getEncryptedData() == null || r.getEncryptedData().isBlank()) {
            throw new IllegalArgumentException("file_empty");
        }

        byte[] bytes = fileService.decryptForAdminPreview(r);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("file_empty");
        }
        List<FlightXlsxPreviewResponse.SheetPreview> sheets = parseAllSheets(bytes, maxRowsPerSheet);

        FlightXlsxPreviewResponse resp = new FlightXlsxPreviewResponse();
        resp.setFileId(r.getId());
        resp.setOriginalName(r.getOriginalName());
        resp.setSheets(sheets);
        return resp;
    }

    private List<FlightXlsxPreviewResponse.SheetPreview> parseAllSheets(byte[] bytes, int maxRowsPerSheet) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            int maxRows = Math.max(1, maxRowsPerSheet);
            List<FlightXlsxPreviewResponse.SheetPreview> out = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                if (sheet == null) {
                    continue;
                }
                ParsedSheet parsed = parseSheet(sheet, maxRows);
                FlightXlsxPreviewResponse.SheetPreview sp = new FlightXlsxPreviewResponse.SheetPreview();
                sp.setName(sheet.getSheetName());
                sp.setColumns(parsed.columns);
                sp.setRows(parsed.rows);
                sp.setTotalRows(parsed.totalRows);
                out.add(sp);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("xlsx_parse_failed", e);
        }
    }

    private ParsedSheet parseSheet(Sheet sheet, int maxRows) {
        int headerRowIndex = findHeaderRowIndex(sheet);
        if (headerRowIndex < 0) {
            return new ParsedSheet(List.of(), List.of(), 0);
        }
        Row headerRow = sheet.getRow(headerRowIndex);
        int lastCol = headerRow == null ? -1 : headerRow.getLastCellNum();
        if (lastCol <= 0) {
            return new ParsedSheet(List.of(), List.of(), 0);
        }

        List<String> columns = new ArrayList<>();
        for (int c = 0; c < lastCol; c++) {
            String name = sanitize(cellToString(headerRow.getCell(c)));
            if (name.isBlank()) {
                name = "COL_" + (c + 1);
            }
            columns.add(name);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int totalRows = 0;
        int startRow = headerRowIndex + 1;
        while (startRow <= sheet.getLastRowNum()) {
            Row maybeUnitRow = sheet.getRow(startRow);
            if (maybeUnitRow == null) {
                startRow++;
                continue;
            }
            if (!looksLikeUnitsRow(maybeUnitRow, columns.size())) {
                break;
            }
            startRow++;
        }

        for (int r = startRow; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            boolean any = false;
            for (int c = 0; c < columns.size(); c++) {
                Object v = cellToValue(row.getCell(c));
                if (v != null && !(v instanceof String s && s.isBlank())) {
                    any = true;
                }
                m.put(columns.get(c), v);
            }
            if (!any) {
                continue;
            }
            totalRows++;
            if (rows.size() < maxRows) {
                rows.add(m);
            }
        }

        return new ParsedSheet(columns, rows, totalRows);
    }

    private boolean looksLikeUnitsRow(Row row, int colCount) {
        if (row == null) {
            return false;
        }
        String first = sanitize(cellToString(row.getCell(0))).toLowerCase();
        if (first.contains("单位") || first.equals("unit") || first.contains("units")) {
            return true;
        }
        int sampleCols = Math.min(colCount, Math.max(1, row.getLastCellNum()));
        int nonBlank = 0;
        int numeric = 0;
        int unitLike = 0;
        for (int c = 0; c < sampleCols; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                continue;
            }
            CellType t = cell.getCellType();
            if (t == CellType.FORMULA) {
                t = cell.getCachedFormulaResultType();
            }
            if (t == CellType.NUMERIC) {
                numeric++;
                nonBlank++;
                continue;
            }
            String s = sanitize(cellToString(cell));
            if (s.isBlank()) {
                continue;
            }
            nonBlank++;
            String sl = s.toLowerCase();
            if (sl.contains("ft") || sl.contains("kt") || sl.contains("deg") || s.contains("°") || s.contains("/") || s.contains("(") || s.contains(")") || s.contains("英尺") || s.contains("节") || s.contains("度") || s.contains("分钟")) {
                unitLike++;
            }
        }
        if (nonBlank == 0) {
            return false;
        }
        if (numeric > 0) {
            return false;
        }
        return unitLike >= Math.max(2, nonBlank * 2 / 3);
    }

    private int findHeaderRowIndex(Sheet sheet) {
        int first = Math.max(0, sheet.getFirstRowNum());
        int last = Math.min(sheet.getLastRowNum(), first + 50);
        int bestRow = -1;
        int bestNonBlank = 0;
        for (int r = first; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int nonBlank = 0;
            short lastCell = row.getLastCellNum();
            for (int c = 0; c < lastCell; c++) {
                String s = sanitize(cellToString(row.getCell(c)));
                if (!s.isBlank()) {
                    nonBlank++;
                }
            }
            if (nonBlank > bestNonBlank) {
                bestNonBlank = nonBlank;
                bestRow = r;
            }
        }
        return bestNonBlank > 0 ? bestRow : -1;
    }

    private String cellToString(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            return formatter.formatCellValue(cell);
        } catch (Exception e) {
            return "";
        }
    }

    private Object cellToValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType t = cell.getCellType();
        if (t == CellType.FORMULA) {
            t = cell.getCachedFormulaResultType();
        }
        if (t == CellType.NUMERIC) {
            try {
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue() == null ? null : cell.getDateCellValue().toInstant().toString();
                }
            } catch (Exception e) {
            }
            return cell.getNumericCellValue();
        }
        if (t == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        if (t == CellType.BLANK) {
            return null;
        }
        String s = sanitize(cellToString(cell));
        return s.isBlank() ? null : s;
    }

    private static String sanitize(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\u0000", "").replace("\r", " ").replace("\n", " ").trim();
        return s;
    }

    private static boolean isXlsx(FileRecordEntity r) {
        String n = r.getOriginalName() == null ? "" : r.getOriginalName().toLowerCase();
        String ct = r.getContentType() == null ? "" : r.getContentType().toLowerCase();
        return n.endsWith(".xlsx") || ct.contains("spreadsheetml") || ct.contains("ms-excel");
    }

    private static class ParsedSheet {
        public final List<String> columns;
        public final List<Map<String, Object>> rows;
        public final int totalRows;

        public ParsedSheet(List<String> columns, List<Map<String, Object>> rows, int totalRows) {
            this.columns = columns;
            this.rows = rows;
            this.totalRows = totalRows;
        }
    }
}
