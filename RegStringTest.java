import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ClassName RegStringTest
 * @Description  正则匹配串测试工具
 * @Author StarLee
 * @Date 2022/3/7
 */

public class RegStringTest {
    public static void main(String[] args) {
        Properties properties = new Properties();
        try (FileInputStream fileInputStream = new FileInputStream("RegStringTest.properties");
             BufferedReader bufferedReader = new BufferedReader(
                     new InputStreamReader(fileInputStream, StandardCharsets.UTF_8))){
            properties.load(bufferedReader);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        String regStr = properties.get("RegStringTest").toString();
        Pattern compile = Pattern.compile(regStr);
        print(regStr);
        Scanner scanner = new Scanner(System.in);
        StringBuilder sb = new StringBuilder();
        String s;
        while (scanner.hasNextLine()){
            s = scanner.nextLine();
            if (Objects.equals(s,"exit")) break;
            sb.append(s).append("\n");
        }
        System.out.println("=======================匹配结果============================");
        Matcher matcher = compile.matcher(sb);
        while (matcher.find()){
            System.out.println(matcher.group(0));
        }
    }

    private static void print(String regStr){
        System.out.println("________________________________________________________________________");
        System.out.println("|\t当你运行此程序时,表明你正在测试你的正则表达式。。。。\t\t|");
        System.out.println("|\t将从RegStringTest.properties中读取你的表达式\t\t\t|");
        System.out.println("|\t从配置文件中读取为 " + regStr);
        System.out.println("------------------------------------------------------------------------");
        System.out.println("|\t这是一个教程:\t\t|\n|\t请输入测试用例:\t\t|\n|\tdasdadadsdada\t\t|\n|\texit\t\t\t|");
        System.out.println("---------------------------------");
        System.out.println("请输入测试用例【输入exit获取结果】");
    }
}
