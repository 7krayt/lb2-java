package minidb.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

public class Row implements Serializable {
    private static final long serialVersionUID = 1L;

    public final long id;
    public final Map<String, Object> values;

    public Row(long id, Map<String, Object> values) {
        this.id = id;
        this.values = new LinkedHashMap<String, Object>(values);
    }
}
