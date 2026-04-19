package com.example.serializer;

import com.example.extension.ExtensionLoader;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序列化器注册表 - 基于 SPI 懒加载单例
 */
@Slf4j
public class SerializerFactory {
    private static volatile SerializerFactory INSTANCE;

    private final Map<Byte, String> codeToName = new ConcurrentHashMap<>();
    private final Map<String, Byte> nameToCode = new ConcurrentHashMap<>();
    private final Map<Byte, Serializer> codeToInstance = new ConcurrentHashMap<>();

    private SerializerFactory() {
        loadFromExtension();
    }

    public static SerializerFactory getInstance() {
        if (INSTANCE == null) {
            synchronized (SerializerFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SerializerFactory();
                }
            }
        }
        return INSTANCE;
    }

    private void loadFromExtension() {
        // 加载所有序列化器实现类
        Map<String, Class<?>> loadedClasses = ExtensionLoader.load(Serializer.class);

        // 遍历所有序列化器，建立映射
        for (Map.Entry<String, Class<?>> entry : loadedClasses.entrySet()) {
            String key = entry.getKey();
            try {
                Class<?> clazz = entry.getValue();
                Serializer instance = (Serializer) clazz.getDeclaredConstructor().newInstance();
                byte code = instance.getCode();
                String name = instance.getName();

                if (codeToName.containsKey(code)) {
                    log.warn("序列化器编号冲突: {} 已被 {} 占用，跳过 {}",
                            code, codeToName.get(code), name);
                    continue;
                }

                codeToName.put(code, name);
                nameToCode.put(name, code);
                codeToInstance.put(code, instance);

                log.debug("注册序列化器: {} -> {}", code, name);
            } catch (Exception e) {
                log.error("加载序列化器失败: {}", key, e);
            }
        }
    }

    /**
     * 根据编号获取序列化器名称
     */
    public String getNameByCode(byte code) {
        String name = codeToName.get(code);
        if (name == null) {
            throw new IllegalArgumentException("未知的序列化器编号: " + code);
        }
        return name;
    }

    /**
     * 根据名称获取序列化器编号
     */
    public byte getCodeByName(String name) {
        Byte code = nameToCode.get(name);
        if (code == null) {
            throw new IllegalArgumentException("未知的序列化器: " + name);
        }
        return code;
    }

    /**
     * 根据编号获取序列化器实例
     */
    public Serializer getByCode(byte code) {
        Serializer serializer = codeToInstance.get(code);
        if (serializer == null) {
            throw new IllegalArgumentException("未知的序列化器编号: " + code);
        }
        return serializer;
    }

    /**
     * 获取所有已注册的序列化器名称
     */
    public Set<String> getAllNames() {
        return nameToCode.keySet();
    }

    /**
     * 检查序列化器是否已注册
     */
    public boolean contains(String name) {
        return nameToCode.containsKey(name);
    }

    /**
     * 重置单例（仅用于测试）
     */
    static void reset() {
        INSTANCE = null;
    }
}