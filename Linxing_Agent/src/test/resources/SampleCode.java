package com.example.demo;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * 示例 Java 类，用于测试 CodeChunkStrategy
 */
public class SampleCode {

    private String name;
    private int age;
    private List<String> hobbies;

    public SampleCode() {
        this.name = "Unknown";
        this.age = 0;
        this.hobbies = new ArrayList<>();
    }

    public SampleCode(String name, int age) {
        this.name = name;
        this.age = age;
        this.hobbies = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public void addHobby(String hobby) {
        this.hobbies.add(hobby);
    }

    public List<String> getHobbies() {
        return new ArrayList<>(hobbies);
    }

    public String introduce() {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello, my name is ").append(name);
        sb.append(", I am ").append(age).append(" years old.");
        if (!hobbies.isEmpty()) {
            sb.append(" My hobbies are: ");
            sb.append(String.join(", ", hobbies));
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("age", age);
        map.put("hobbies", hobbies);
        return map;
    }

    public static SampleCode fromMap(Map<String, Object> map) {
        String name = (String) map.get("name");
        Integer age = (Integer) map.get("age");
        SampleCode obj = new SampleCode(name, age);
        @SuppressWarnings("unchecked")
        List<String> hobbies = (List<String>) map.get("hobbies");
        if (hobbies != null) {
            obj.hobbies = new ArrayList<>(hobbies);
        }
        return obj;
    }

    @Override
    public String toString() {
        return "SampleCode{name='" + name + "', age=" + age + ", hobbies=" + hobbies + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SampleCode other = (SampleCode) obj;
        return age == other.age && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + age;
    }
}

class HelperClass {

    public static void printInfo(SampleCode sample) {
        System.out.println(sample.introduce());
    }

    public static void main(String[] args) {
        SampleCode sample = new SampleCode("Alice", 25);
        sample.addHobby("Reading");
        sample.addHobby("Coding");
        sample.addHobby("Gaming");
        printInfo(sample);
    }
}

interface DataProcessor {

    void process(String data);

    String getResult();
}

abstract class AbstractProcessor implements DataProcessor {

    protected String result;

    @Override
    public String getResult() {
        return result;
    }

    protected abstract void validate(String data);
}

class ConcreteProcessor extends AbstractProcessor {

    @Override
    public void process(String data) {
        validate(data);
        this.result = data.toUpperCase();
    }

    @Override
    protected void validate(String data) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
    }
}
