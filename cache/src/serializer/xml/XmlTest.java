package serializer.xml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import serializer.Color;
import serializer.Grade;
import serializer.Student;

import com.thoughtworks.xstream.XStream;

public class XmlTest {
	public static void main(String[] args) {
		testXml();
	}
	
	public static void testXml(){
		 XStream xstream = new XStream();

	        String serialValue = xstream.toXML(new Student("wang", 20));
	        System.out.println(serialValue);
	        Student student = (Student)xstream.fromXML(serialValue);
	        System.out.println(student.getName());
	        
	        //数组
	        String[] arr1 = {"111", "222"};
	        serialValue = xstream.toXML(arr1);
	        System.out.println(serialValue);
	        String[] arr2 = (String[])xstream.fromXML(serialValue);
	        System.out.println(arr2[0]);
	        
	        //集合
	        List<Student> list1 = new ArrayList<Student>();
	        list1.add(new Student("wang", 20));
	        list1.add(new Student("zhang", 21));
	        serialValue = xstream.toXML(list1);
	        System.out.println(serialValue);
	        List<Student> list2 = (List<Student>)xstream.fromXML(serialValue);
	        System.out.println(list2.get(0).getName());
	        
	        //MAP
	        Map<Integer, String> map1 = new HashMap<Integer, String>();
	        map1.put(1, "11");
	        map1.put(2, "22");
	        serialValue = xstream.toXML(map1);
	        System.out.println(serialValue);
	        Map<Integer, String> map2 = (Map<Integer, String>)xstream.fromXML(serialValue);
	        System.out.println(map2.get(1));
	        
	        //枚举
	        Color color = Color.RED;
	        serialValue = xstream.toXML(color);
	        System.out.println(serialValue);
	        Color color2 = (Color)xstream.fromXML(serialValue);
	        System.out.println(color2);
	        
	        //注入测试
	        serialValue = xstream.toXML(new Student("</name><wang>yuan</wang>", 20));
	        System.out.println(serialValue);
	        Student student3 = (Student)xstream.fromXML(serialValue);
	        System.out.println(student3.getName());
	        
	        serialValue = xstream.toXML(new Grade());
	        System.out.println(serialValue);
	        Grade g = (Grade)xstream.fromXML(serialValue);
	        System.out.println(g.name);
	}
}
