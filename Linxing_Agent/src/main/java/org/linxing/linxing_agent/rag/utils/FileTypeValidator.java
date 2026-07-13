package org.linxing.linxing_agent.rag.utils;

import java.util.Set;

/**
 * 文件类型校验与归一化工具
 * 统一白名单与扩展名归一化逻辑，避免 controller 与 service 各维护一套
 */
public final class FileTypeValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "text", "pdf", "doc", "docx",
            "xls", "xlsx", "java", "csv", "html", "htm"
    );

    private FileTypeValidator() {
    }

    /**
     * 校验文件名扩展名是否在允许的白名单内
     * @param fileName 文件名
     * @return 允许返回true，否则false
     */
    public static boolean isAllowed(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dotIndex + 1).toLowerCase();
        return ALLOWED_EXTENSIONS.contains(ext);
    }

    /**
     * 返回允许的扩展名集合（用于错误提示）
     */
    public static Set<String> allowedExtensions() {
        return ALLOWED_EXTENSIONS;
    }

    /**
     * 将文件名扩展名归一化为下游 chunk 决策使用的类型标识
     * @param fileName 文件名
     * @return 归一化类型；无法识别时返回原始扩展名或"unknown"
     */
    public static String normalizedType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "pdf" -> "pdf";
            case "doc", "docx" -> "docx";
            case "xls", "xlsx" -> "xlsx";
            case "txt", "text" -> "txt";
            case "md" -> "md";
            case "java" -> "java";
            case "csv" -> "csv";
            case "html", "htm" -> "html";
            default -> extension;
        };
    }
}
