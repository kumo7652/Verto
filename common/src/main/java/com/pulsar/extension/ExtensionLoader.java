package com.pulsar.extension;

import com.pulsar.constant.RpcConstant;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPI 扩展加载器 - imports 风格
 * 配置文件格式：每行一个全限定类名
 * 元数据通过 @SpiExtension 注解声明
 */
@Slf4j
public class ExtensionLoader {
    /**
     * 存储已加载的类：接口名 => (name => 实现类)
     */
    private static final Map<String, Map<String, Class<?>>> loaderMap = new ConcurrentHashMap<>();

    /**
     * 存储已加载的类：接口名 => (code => 实现类)
     */
    private static final Map<String, Map<Integer, Class<?>>> codeMap = new ConcurrentHashMap<>();

    /**
     * 对象实例缓存：类名 => 实例
     */
    private static final Map<String, Object> instanceCache = new ConcurrentHashMap<>();

    /**
     * 加载某个接口的所有扩展实现
     */
    public static Map<String, Class<?>> load(Class<?> interfaceClass) {
        String interfaceName = interfaceClass.getName();
        if (loaderMap.containsKey(interfaceName)) {
            return loaderMap.get(interfaceName);
        }

        log.info("加载 SPI 扩展: {}", interfaceName);

        Map<String, Class<?>> nameToClass = new HashMap<>();
        Map<Integer, Class<?>> codeToClass = new HashMap<>();

        String fileName = RpcConstant.RPC_SPI_DIR + interfaceName + ".imports";

        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = classLoader.getResources(fileName);

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                log.debug("读取 SPI 配置: {}", url);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                    String className;
                    while ((className = reader.readLine()) != null) {
                        className = className.trim();
                        if (className.isEmpty() || className.startsWith("#")) {
                            continue;
                        }

                        try {
                            Class<?> implClass = Class.forName(className);
                            SpiExtension annotation = implClass.getAnnotation(SpiExtension.class);

                            if (annotation == null) {
                                log.warn("类 {} 缺少 @SpiExtension 注解，跳过", className);
                                continue;
                            }

                            String name = annotation.name();
                            int code = annotation.code();
                            boolean override = annotation.override();

                            // 处理 override 逻辑
                            if (nameToClass.containsKey(name)) {
                                if (override) {
                                    log.info("扩展 {} 被 {} 覆盖", name, className);
                                    nameToClass.put(name, implClass);
                                } else {
                                    log.debug("扩展 {} 已存在，跳过 {}", name, className);
                                }
                            } else {
                                nameToClass.put(name, implClass);
                            }

                            // code 映射
                            if (code != 0) {
                                if (codeToClass.containsKey(code) && override) {
                                    codeToClass.put(code, implClass);
                                } else if (!codeToClass.containsKey(code)) {
                                    codeToClass.put(code, implClass);
                                }
                            }

                            log.debug("注册扩展: name={}, code={}, class={}", name, code, className);

                        } catch (ClassNotFoundException e) {
                            log.error("无法加载类: {}", className, e);
                        }
                    }
                } catch (Exception e) {
                    log.error("读取 SPI 配置失败: {}", url, e);
                }
            }
        } catch (Exception e) {
            log.error("加载 SPI 扩展失败: {}", interfaceName, e);
        }

        loaderMap.put(interfaceName, nameToClass);
        codeMap.put(interfaceName, codeToClass);

        return nameToClass;
    }

    /**
     * 根据名称获取扩展实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T getInstance(Class<T> interfaceClass, String name) {
        String interfaceName = interfaceClass.getName();

        Map<String, Class<?>> nameToClass = loaderMap.get(interfaceName);
        if (nameToClass == null) {
            nameToClass = load(interfaceClass);
        }

        Class<?> implClass = nameToClass.get(name);
        if (implClass == null) {
            throw new RuntimeException(String.format("未找到扩展: %s#%s", interfaceName, name));
        }

        return (T) getOrCreateInstance(implClass);
    }

    /**
     * 根据编码获取扩展实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T getInstanceByCode(Class<T> interfaceClass, int code) {
        String interfaceName = interfaceClass.getName();

        Map<Integer, Class<?>> codeToClass = codeMap.get(interfaceName);
        if (codeToClass == null) {
            load(interfaceClass);
            codeToClass = codeMap.get(interfaceName);
        }

        Class<?> implClass = codeToClass.get(code);
        if (implClass == null) {
            throw new RuntimeException(String.format("未找到扩展: %s#code=%d", interfaceName, code));
        }

        return (T) getOrCreateInstance(implClass);
    }

    /**
     * 获取或创建实例（单例）
     */
    private static Object getOrCreateInstance(Class<?> implClass) {
        String className = implClass.getName();
        if (!instanceCache.containsKey(className)) {
            try {
                Constructor<?> constructor = implClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                instanceCache.put(className, constructor.newInstance());
            } catch (Exception e) {
                throw new RuntimeException("创建实例失败: " + className, e);
            }
        }
        return instanceCache.get(className);
    }

    /**
     * 获取扩展的编码
     */
    public static int getCode(Class<?> interfaceClass, String name) {
        Map<String, Class<?>> nameToClass = loaderMap.get(interfaceClass.getName());
        if (nameToClass == null) {
            nameToClass = load(interfaceClass);
        }

        Class<?> implClass = nameToClass.get(name);
        if (implClass == null) {
            throw new RuntimeException(String.format("未找到扩展: %s#%s", interfaceClass.getName(), name));
        }

        SpiExtension annotation = implClass.getAnnotation(SpiExtension.class);
        return annotation != null ? annotation.code() : 0;
    }

    /**
     * 获取扩展的名称
     */
    public static String getName(Class<?> interfaceClass, int code) {
        Map<Integer, Class<?>> codeToClass = codeMap.get(interfaceClass.getName());
        if (codeToClass == null) {
            load(interfaceClass);
            codeToClass = codeMap.get(interfaceClass.getName());
        }

        Class<?> implClass = codeToClass.get(code);
        if (implClass == null) {
            throw new RuntimeException(String.format("未找到扩展: %s#code=%d", interfaceClass.getName(), code));
        }

        SpiExtension annotation = implClass.getAnnotation(SpiExtension.class);
        return annotation != null ? annotation.name() : null;
    }
}