package org.testng.xml;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public class SuiteGenerator {
  private static final Collection<String> EMPTY_CLASS_LIST = Collections.emptyList();

  public SuiteGenerator() {
  }

  public static LaunchSuite createProxiedXmlSuite(File xmlSuitePath) {
    return new LaunchSuite.ExistingSuite(xmlSuitePath);
  }

  public static LaunchSuite createSuite(String projectName, Collection<String> packageNames, Map<String, Collection<String>> classAndMethodNames, Collection<String> groupNames, Map<String, String> parameters, String annotationType, int logLevel) {
    Collection<String> classes = classAndMethodNames != null ? classAndMethodNames.keySet() : EMPTY_CLASS_LIST;
    Object result;
    if (null != groupNames && !groupNames.isEmpty()) {
      result = new LaunchSuite.ClassListSuite(projectName, packageNames, (Collection)classes, groupNames, parameters, annotationType, logLevel);
    } else if (packageNames != null && packageNames.size() > 0) {
      result = new LaunchSuite.ClassListSuite(projectName, packageNames, (Collection)classes, groupNames, parameters, annotationType, logLevel);
    } else {
      result = new LaunchSuite.ClassesAndMethodsSuite(projectName, classAndMethodNames, parameters, annotationType, logLevel);
    }

    return (LaunchSuite)result;
  }
}
