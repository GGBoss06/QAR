package com.qar.securitysystem.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qar.securitysystem.dto.QarTableRowRequest;
import com.qar.securitysystem.dto.QarTableRowResponse;
import com.qar.securitysystem.dto.QarXlsxPreviewResponse;
import com.qar.securitysystem.config.FileStorageProperties;
import com.qar.securitysystem.model.QarTableRowEntity;
import com.qar.securitysystem.repo.QarTableRowRepository;
import com.qar.securitysystem.util.IdUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Base64;

@Service
public class QarTableService {
    private final QarTableRowRepository repo;
    private final ObjectMapper objectMapper;
    private final FileStorageProperties fileStorageProperties;
    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    public QarTableService(QarTableRowRepository repo, ObjectMapper objectMapper, FileStorageProperties fileStorageProperties) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.fileStorageProperties = fileStorageProperties;
    }

    public List<QarTableRowResponse> list(String sortBy, String sortDir) {
        String normalizedSort = normalizeDatabaseSortProperty(sortBy);
        if (normalizedSort != null) {
            Sort.Direction direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
            return repo.findAll(Sort.by(direction, normalizedSort)).stream().map(this::toResponse).toList();
        }
        List<QarTableRowEntity> all = repo.findAll();
        Comparator<QarTableRowEntity> cmp = buildComparator(sortBy);
        if ("desc".equalsIgnoreCase(sortDir)) {
            cmp = cmp.reversed();
        }
        all.sort(cmp);
        return all.stream().map(this::toResponse).toList();
    }

    public QarTableRowResponse create(QarTableRowRequest req) {
        if (req == null || req.getData() == null || req.getData().isEmpty()) {
            throw new IllegalArgumentException("data_required");
        }
        QarTableRowEntity e = new QarTableRowEntity();
        e.setId(IdUtil.newId());
        e.setCreatedAt(Instant.now());
        e.setDataJson(writeJson(req.getData()));
        repo.save(e);
        return toResponse(e);
    }

    public QarTableRowResponse update(String id, QarTableRowRequest req) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id_required");
        }
        QarTableRowEntity e = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("not_found"));
        if (req != null && req.getData() != null && !req.getData().isEmpty()) {
            e.setDataJson(writeJson(req.getData()));
            e.setUpdatedAt(Instant.now());
            repo.save(e);
        }
        return toResponse(e);
    }

    public QarXlsxPreviewResponse previewXlsx(MultipartFile file, int maxRows) {
        ParsedXlsx parsed = parseXlsx(file, maxRows);
        QarXlsxPreviewResponse resp = new QarXlsxPreviewResponse();
        resp.setColumns(parsed.columns);
        resp.setRows(parsed.rows);
        return resp;
    }

    @Transactional
    public int importXlsx(MultipartFile file) {
        ParsedXlsx parsed = parseXlsx(file, Integer.MAX_VALUE);
        return persistRows(parsed.rows);
    }

    public QarXlsxPreviewResponse previewXlsxBase64(String dataBase64, int maxRows) {
        ParsedXlsx parsed = parseXlsxBytes(dataBase64, maxRows);
        QarXlsxPreviewResponse resp = new QarXlsxPreviewResponse();
        resp.setColumns(parsed.columns);
        resp.setRows(parsed.rows);
        return resp;
    }

    @Transactional
    public int importXlsxBase64(String dataBase64) {
        ParsedXlsx parsed = parseXlsxBytes(dataBase64, Integer.MAX_VALUE);
        return persistRows(parsed.rows);
    }

    private ParsedXlsx parseXlsx(MultipartFile file, int maxRows) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file_required");
        }
        if (file.getSize() > fileStorageProperties.getMaxBytes()) {
            throw new IllegalArgumentException("file_too_large");
        }
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            return parseWorkbook(wb, maxRows);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("xlsx_parse_failed", e);
        }
    }

    private ParsedXlsx parseXlsxBytes(String dataBase64, int maxRows) {
        if (dataBase64 == null || dataBase64.isBlank()) {
            throw new IllegalArgumentException("file_required");
        }
        if (encodedPayloadExceedsLimit(dataBase64.trim())) {
            throw new IllegalArgumentException("file_too_large");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(dataBase64.trim());
            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                return parseWorkbook(wb, maxRows);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("xlsx_parse_failed", e);
        }
    }

    private ParsedXlsx parseWorkbook(Workbook wb, int maxRows) {
            Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
            if (sheet == null) {
                throw new IllegalArgumentException("sheet_not_found");
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("header_row_missing");
            }

            List<String> columns = new ArrayList<>();
            for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
                Cell cell = headerRow.getCell(c);
                String name = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (name.isBlank()) {
                    name = "COL_" + (c + 1);
                }
                columns.add(name);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            int firstDataRow = headerRow.getRowNum() + 1;
            for (int r = firstDataRow; r <= sheet.getLastRowNum(); r++) {
                if (rows.size() >= maxRows) {
                    break;
                }
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < columns.size(); c++) {
                    Cell cell = row.getCell(c);
                    Object v = cellToValue(cell);
                    if (v != null && !(v instanceof String s && s.isBlank())) {
                        any = true;
                    }
                    m.put(columns.get(c), v);
                }
                if (!any) {
                    continue;
                }
                rows.add(m);
            }

            return new ParsedXlsx(columns, rows);
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
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue() == null ? null : cell.getDateCellValue().toInstant().toString();
            }
            return cell.getNumericCellValue();
        }
        if (t == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        if (t == CellType.BLANK) {
            return null;
        }
        String s = formatter.formatCellValue(cell);
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isBlank() ? null : v;
    }

    private QarTableRowResponse toResponse(QarTableRowEntity e) {
        QarTableRowResponse r = new QarTableRowResponse();
        r.setId(e.getId());
        r.setData(readJson(e.getDataJson()));
        r.setCreatedAt(e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        r.setUpdatedAt(e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        return r;
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalArgumentException("json_encode_failed", e);
        }
    }

    private int persistRows(List<Map<String, Object>> rows) {
        Instant now = Instant.now();
        List<QarTableRowEntity> entities = rows.stream().map(row -> {
            QarTableRowEntity entity = new QarTableRowEntity();
            entity.setId(IdUtil.newId());
            entity.setCreatedAt(now);
            entity.setDataJson(writeJson(row));
            return entity;
        }).toList();
        repo.saveAll(entities);
        return entities.size();
    }

    private boolean encodedPayloadExceedsLimit(String encodedData) {
        long maxEncodedLength = ((fileStorageProperties.getMaxBytes() + 2L) / 3L) * 4L;
        return encodedData.length() > maxEncodedLength;
    }

    private Comparator<QarTableRowEntity> buildComparator(String sortBy) {
        String s = sortBy == null ? "" : sortBy.trim();
        if (s.isBlank() || "createdAt".equalsIgnoreCase(s)) {
            return Comparator.comparing(QarTableRowEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        if ("updatedAt".equalsIgnoreCase(s)) {
            return Comparator.comparing(QarTableRowEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        }
        if ("id".equalsIgnoreCase(s)) {
            return Comparator.comparing(QarTableRowEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        }

        return (a, b) -> {
            Map<String, Object> da = readJson(a.getDataJson());
            Map<String, Object> db = readJson(b.getDataJson());
            Object va = da.get(s);
            Object vb = db.get(s);
            String sa = va == null ? "" : String.valueOf(va);
            String sb = vb == null ? "" : String.valueOf(vb);
            return sa.compareTo(sb);
        };
    }

    private static String normalizeDatabaseSortProperty(String sortBy) {
        String value = sortBy == null ? "" : sortBy.trim();
        if (value.isBlank() || "createdAt".equalsIgnoreCase(value)) {
            return "createdAt";
        }
        if ("updatedAt".equalsIgnoreCase(value)) {
            return "updatedAt";
        }
        if ("id".equalsIgnoreCase(value)) {
            return "id";
        }
        return null;
    }

    private static class ParsedXlsx {
        public final List<String> columns;
        public final List<Map<String, Object>> rows;

        public ParsedXlsx(List<String> columns, List<Map<String, Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }
}
