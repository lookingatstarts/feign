package feign;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TypesTest {

  @Test
  public void test(){
    List<String> t = new ArrayList<>();
    Class<?> rawType = Types.getRawType(t.getClass());
  }
}