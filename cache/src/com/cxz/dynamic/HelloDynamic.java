package com.cxz.dynamic;

import java.lang.reflect.Constructor;
import java.util.Iterator;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanConstructorInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ReflectionException;

public class HelloDynamic implements DynamicMBean {

	private String name = null;

	private MBeanInfo mBeanInfo = null;

	private String className = null;

	private String description = null;

	private MBeanAttributeInfo[] attributes = null;

	private MBeanConstructorInfo[] constructors = null;

	private MBeanOperationInfo[] operations = null;

	MBeanNotificationInfo[] mBeanNotificationInfoArray = null;

	public HelloDynamic() {
		init();
		buildDynamicMBean();
	}

	private void buildDynamicMBean() {
		Constructor[] thisConstructors = this.getClass().getConstructors();
		// create the constructors.
		constructors[0] = new MBeanConstructorInfo(
				"HelloDynamic(): Constructs a HelloDynamic object",
				thisConstructors[0]);
		// define the attribute of a mbean and its getter/setter
		attributes[0] = new MBeanAttributeInfo("Name", "java.lang.String",
				"Name:name string.", true, true, false);
		MBeanParameterInfo[] params = null;
		// Define the operation array.
		operations[0] = new MBeanOperationInfo("print",
				"print(): print the name", params, "void",
				MBeanOperationInfo.INFO);
		// Dynamically create an instance of a MBean.
		// Includes:attributes, constructors, operations, notifications
		mBeanInfo = new MBeanInfo(className, description, attributes,
				constructors, operations, mBeanNotificationInfoArray);
	}

	private void init() {
		className = this.getClass().getName();
		description = "Simple implementation of a dynamic MBean.";
		attributes = new MBeanAttributeInfo[1];
		constructors = new MBeanConstructorInfo[1];
		operations = new MBeanOperationInfo[1];
		mBeanNotificationInfoArray = new MBeanNotificationInfo[1];
	}

	/**
	 * Add a new operation to the MBean Dynamically
	 */
	private void dynamicAddOperation() {
		init();
		operations = new MBeanOperationInfo[2];
		buildDynamicMBean();
		operations[1] = new MBeanOperationInfo("print1",
				"print1(): print the name", null, "void",
				MBeanOperationInfo.INFO);
		mBeanInfo = new MBeanInfo(className, description, attributes,
				constructors, operations, mBeanNotificationInfoArray);

	}

	public Object getAttribute(String attribute)
			throws AttributeNotFoundException, MBeanException,
			ReflectionException {
		if (attribute == null) {
			return null;
		} else if ("Name".equals(attribute)) {
			return name;
		} else {
			return null;
		}
	}

	public AttributeList getAttributes(String[] attributeNames) {
		if (attributes == null) {
			return null;
		} else {
			AttributeList resultList = new AttributeList();
			if (attributes.length == 0) {
				return resultList;
			} else {
				for (int i = 0; i < attributeNames.length; i++) {
					try {
						Object value = getAttribute(attributeNames[i]);
						resultList.add(new Attribute(attributeNames[i], value));
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			}
			return resultList;
		}

	}

	public MBeanInfo getMBeanInfo() {
		return mBeanInfo;
	}

	public Object invoke(String actionName, Object[] params, String[] signature)
			throws MBeanException, ReflectionException {
		if (actionName.equals("print")) {
			System.out.println("Hello, " + name + ", this is HelloDynamic!");
			dynamicAddOperation();
		} else if (actionName.equals("print1")) {
			System.out.println("This is the dynamic operation");
		} else {
			throw new ReflectionException(new NoSuchMethodException(
					actionName), "Cannot find the operation "
					+ actionName + " in " + className);
		}
		return null;
	}

	public void setAttribute(Attribute attribute)
			throws AttributeNotFoundException, InvalidAttributeValueException,
			MBeanException, ReflectionException {
		if (attribute == null) {
			return;
		} else {
			String name = attribute.getName();
			Object value = attribute.getValue();
			try {
				if (name.equals("Name")) {
					if (value == null) {
						name = null;
					} else if ((Class.forName("java.lang.String"))
							.isAssignableFrom(value.getClass())) {
						this.name = (String) value;
					}
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	public AttributeList setAttributes(AttributeList attributes) {
		if (attributes == null)
			return null;
		AttributeList resultList = new AttributeList();
		if (attributes.isEmpty()) {
			return resultList;
		}
		for (Iterator i = attributes.iterator(); i.hasNext();) {
			Attribute attr = (Attribute) i.next();
			try {
				setAttribute(attr);
				String name = attr.getName();
				Object value = getAttribute(name);
				resultList.add(new Attribute(name, value));
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		return resultList;
	}

}
