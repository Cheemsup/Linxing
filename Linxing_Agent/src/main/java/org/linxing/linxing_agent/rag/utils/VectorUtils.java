package org.linxing.linxing_agent.rag.utils;

public class VectorUtils {

    public static String toArray(float[] vector) {
        if (vector != null && vector.length != 0) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(vector[i]);
            }
            sb.append("]");
            return sb.toString();
        } else {
            return "[0]";
        }
    }
}
