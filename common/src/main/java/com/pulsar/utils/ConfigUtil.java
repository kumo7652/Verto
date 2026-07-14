package com.pulsar.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.*;

public final class ConfigUtil {
    private static final Logger log = LoggerFactory.getLogger(ConfigUtil.class);

    private ConfigUtil() {
    }

    public static <T> T loadConfig(Class<T> clazz, String prefix) {
        return loadConfig(clazz, prefix, null);
    }

    public static <T> T loadConfig(Class<T> clazz, String prefix, String profile) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 第一层：基础配置文件
        loadClasspathFiles(merged, "application");
        // 第二层：profile 配置文件
        if (profile != null && !profile.isEmpty()) {
            loadClasspathFiles(merged, "application-" + profile);
        }
        // 第三层：系统属性覆盖
        System.getProperties().forEach((k, v) -> merged.put((String) k, v));
        // 第四层：环境变量覆盖
        loadEnvVars(merged);

        return bind(merged, clazz, prefix);
    }

    // ========== 文件加载 ==========

    private static void loadClasspathFiles(Map<String, Object> target, String baseName) {
        for (String ext : List.of(".yml", ".yaml", ".properties")) {
            String filename = baseName + ext;
            InputStream in = ConfigUtil.class.getClassLoader().getResourceAsStream(filename);
            if (in == null) continue;
            log.debug("加载配置文件: {}", filename);
            try (in) {
                if (ext.endsWith(".properties")) {
                    loadProperties(target, in);
                } else {
                    loadYaml(target, in);
                }
            } catch (IOException e) {
                log.warn("读取配置文件失败: {}", filename, e);
            }
        }
    }

    private static void loadProperties(Map<String, Object> target, InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        props.forEach((k, v) -> target.put((String) k, v));
    }

    private static void loadYaml(Map<String, Object> target, InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(in);
        if (map != null) {
            flatten(target, "", map);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> target, String prefix, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> m) {
                flatten(target, key, (Map<String, Object>) m);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    String indexedKey = key + "[" + i + "]";
                    if (item instanceof Map<?, ?> im) {
                        flatten(target, indexedKey, (Map<String, Object>) im);
                    } else {
                        target.put(indexedKey, item);
                    }
                }
            } else {
                target.put(key, value);
            }
        }
    }

    private static void loadEnvVars(Map<String, Object> target) {
        System.getenv().forEach((k, v) -> {
            String key = k.toLowerCase(Locale.ROOT).replace('_', '.');
            target.put(key, v);
        });
    }

    // ========== Bean 绑定 ==========

    private static <T> T bind(Map<String, Object> source, Class<T> clazz, String prefix) {
        try {
            T bean = clazz.getConstructor().newInstance();
            String dotPrefix = prefix.isEmpty() ? "" : prefix + ".";

            for (Field field : clazz.getDeclaredFields()) {
                String key = dotPrefix + toKebabCase(field.getName());
                Class<?> fieldType = field.getType();

                if (isSimpleType(fieldType)) {
                    Object value = source.get(key);
                    if (value != null) {
                        setFieldValue(bean, field, value);
                    }
                } else {
                    // 嵌套对象
                    Object nested = bind(source, fieldType, key);
                    field.setAccessible(true);
                    field.set(bean, nested);
                }
            }
            return bean;
        } catch (Exception e) {
            throw new RuntimeException("配置绑定失败: " + clazz.getName(), e);
        }
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Integer.class || type == Long.class
                || type == Boolean.class || type == Double.class
                || type == Float.class || type == Short.class
                || type == Byte.class;
    }

    private static void setFieldValue(Object bean, Field field, Object value) throws Exception {
        field.setAccessible(true);
        Class<?> type = field.getType();

        if (type == String.class) {
            field.set(bean, value.toString());
        } else if (type == int.class || type == Integer.class) {
            field.set(bean, toInt(value));
        } else if (type == long.class || type == Long.class) {
            field.set(bean, toLong(value));
        } else if (type == boolean.class || type == Boolean.class) {
            field.set(bean, Boolean.parseBoolean(value.toString()));
        } else if (type == double.class || type == Double.class) {
            field.set(bean, Double.parseDouble(value.toString()));
        } else {
            field.set(bean, value);
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }

    /** camelCase → kebab-case，与 Spring Boot 松弛绑定对齐 */
    private static String toKebabCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
