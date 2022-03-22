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
 * @Description  ����ƥ�䴮���Թ���
 * @Author StarLee
 * @Date 2022/3/7
 */

public class RegStringTest {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        StringBuilder sb = new StringBuilder();
        String s;
        print();
        while (scanner.hasNextLine()){
            s = scanner.nextLine();
            if (Objects.equals(s,"exit")) break;
            sb.append(s).append("\n");
        }
        scanner.close();
        System.out.println("=======================ƥ����============================");
        Properties properties = new Properties();
        try (FileInputStream fileInputStream = new FileInputStream("../RegStringTest.properties");
             BufferedReader bufferedReader = new BufferedReader(
                     new InputStreamReader(fileInputStream, StandardCharsets.UTF_8))){
            properties.load(bufferedReader);
        } catch (IOException e) {
            e.printStackTrace();
        }
        String regStr = properties.get("RegStringTest").toString();
        System.out.println(regStr);
        Pattern compile = Pattern.compile(regStr);
        Matcher matcher = compile.matcher(sb);
        while (matcher.find()){
            System.out.println(matcher.group(0));
        }
    }

    private static void print(){
        System.out.println("_____________________________________________________");
        System.out.println("|\t�������д˳���ʱ,���������ڲ������������ʽ��������\t|");
        System.out.println("|\t����RegStringTest.properties�ж�ȡ��ı��ʽ\t\t|");
        System.out.println("-----------------------------------------------------");
        System.out.println("|\t����һ���̳�:\t\t\t|\n|\t�������������:\t\t|\n|\tdasdadadsdada\t\t|\n|\texit\t\t\t\t|");
        System.out.println("-------------------------");
        System.out.println("�������������������exit�˳���");
    }
}
