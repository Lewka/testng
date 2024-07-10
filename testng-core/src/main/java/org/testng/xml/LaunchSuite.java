package org.testng.xml;

import org.testng.collections.Lists;
import org.testng.internal.Utils;
import org.testng.log4testng.Logger;
import org.testng.reporters.XMLStringBuffer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public abstract class LaunchSuite {
  private static final Logger LOGGER = Logger.getLogger(LaunchSuite.class);
  protected boolean m_temporary;

  protected LaunchSuite(boolean isTemp) {
    this.m_temporary = isTemp;
  }

  public boolean isTemporary() {
    return this.m_temporary;
  }

  public abstract File save(File var1);

  public abstract XMLStringBuffer getSuiteBuffer();

  static class ClassListSuite extends CustomizedSuite {
    protected Collection<String> m_packageNames;
    protected Collection<String> m_classNames;
    protected Collection<String> m_groupNames;
    protected int m_logLevel;

    ClassListSuite(String projectName, Collection<String> packageNames, Collection<String> classNames, Collection<String> groupNames, Map<String, String> parameters, String annotationType, int logLevel) {
      super(projectName, "Custom suite", parameters, annotationType);
      this.m_packageNames = packageNames;
      this.m_classNames = classNames;
      this.m_groupNames = groupNames;
      this.m_logLevel = logLevel;
    }

    protected void initContentBuffer(XMLStringBuffer suiteBuffer) {
      Properties testAttrs = new Properties();
      testAttrs.setProperty("name", this.m_projectName);
      testAttrs.setProperty("verbose", String.valueOf(this.m_logLevel));
      suiteBuffer.push("test", testAttrs);
      Iterator var3;
      String className;
      Properties classAttrs;
      if (null != this.m_groupNames) {
        suiteBuffer.push("groups");
        suiteBuffer.push("run");
        var3 = this.m_groupNames.iterator();

        while(var3.hasNext()) {
          className = (String)var3.next();
          classAttrs = new Properties();
          classAttrs.setProperty("name", className);
          suiteBuffer.addEmptyElement("include", classAttrs);
        }

        suiteBuffer.pop("run");
        suiteBuffer.pop("groups");
      }

      if (this.m_packageNames != null && this.m_packageNames.size() > 0) {
        suiteBuffer.push("packages");
        var3 = this.m_packageNames.iterator();

        while(var3.hasNext()) {
          className = (String)var3.next();
          classAttrs = new Properties();
          classAttrs.setProperty("name", className);
          suiteBuffer.addEmptyElement("package", classAttrs);
        }

        suiteBuffer.pop("packages");
      }

      if (this.m_classNames != null && this.m_classNames.size() > 0) {
        suiteBuffer.push("classes");
        var3 = this.m_classNames.iterator();

        while(var3.hasNext()) {
          className = (String)var3.next();
          classAttrs = new Properties();
          classAttrs.setProperty("name", className);
          suiteBuffer.addEmptyElement("class", classAttrs);
        }

        suiteBuffer.pop("classes");
      }

      suiteBuffer.pop("test");
    }
  }

  static class ClassesAndMethodsSuite extends CustomizedSuite {
    protected Map<String, Collection<String>> m_classes;
    protected int m_logLevel;

    ClassesAndMethodsSuite(String projectName, Map<String, Collection<String>> classes, Map<String, String> parameters, String annotationType, int logLevel) {
      super(projectName, "Custom suite", parameters, annotationType);
      this.m_classes = classes;
      this.m_logLevel = logLevel;
    }

    protected void initContentBuffer(XMLStringBuffer suiteBuffer) {
      Properties testAttrs = new Properties();
      testAttrs.setProperty("name", this.m_projectName);
      testAttrs.setProperty("verbose", String.valueOf(this.m_logLevel));
      suiteBuffer.push("test", testAttrs);
      suiteBuffer.push("classes");
      Iterator var3 = this.m_classes.entrySet().iterator();

      while(true) {
        while(var3.hasNext()) {
          Map.Entry<String, Collection<String>> entry = (Map.Entry)var3.next();
          Properties classAttrs = new Properties();
          classAttrs.setProperty("name", (String)entry.getKey());
          Collection<String> methodNames = this.sanitize((Collection)entry.getValue());
          if (null != methodNames && methodNames.size() > 0) {
            suiteBuffer.push("class", classAttrs);
            suiteBuffer.push("methods");
            Iterator var7 = methodNames.iterator();

            while(var7.hasNext()) {
              String methodName = (String)var7.next();
              Properties methodAttrs = new Properties();
              methodAttrs.setProperty("name", methodName);
              suiteBuffer.addEmptyElement("include", methodAttrs);
            }

            suiteBuffer.pop("methods");
            suiteBuffer.pop("class");
          } else {
            suiteBuffer.addEmptyElement("class", classAttrs);
          }
        }

        suiteBuffer.pop("classes");
        suiteBuffer.pop("test");
        return;
      }
    }

    private Collection<String> sanitize(Collection<String> source) {
      if (null == source) {
        return null;
      } else {
        List<String> result = Lists.newArrayList();
        Iterator var3 = source.iterator();

        while(var3.hasNext()) {
          String name = (String)var3.next();
          if (Utils.isStringNotBlank(name)) {
            result.add(name);
          }
        }

        return result;
      }
    }
  }

  static class MethodsSuite extends CustomizedSuite {
    protected Collection<String> m_methodNames;
    protected String m_className;
    protected int m_logLevel;

    MethodsSuite(String projectName, String className, Collection<String> methodNames, Map<String, String> parameters, String annotationType, int logLevel) {
      super(projectName, className, parameters, annotationType);
      this.m_className = className;
      this.m_methodNames = methodNames;
      this.m_logLevel = logLevel;
    }

    protected void initContentBuffer(XMLStringBuffer suiteBuffer) {
      Properties testAttrs = new Properties();
      testAttrs.setProperty("name", this.m_className);
      testAttrs.setProperty("verbose", String.valueOf(this.m_logLevel));
      suiteBuffer.push("test", testAttrs);
      suiteBuffer.push("classes");
      Properties classAttrs = new Properties();
      classAttrs.setProperty("name", this.m_className);
      if (null != this.m_methodNames && this.m_methodNames.size() > 0) {
        suiteBuffer.push("class", classAttrs);
        suiteBuffer.push("methods");
        Iterator var4 = this.m_methodNames.iterator();

        while(var4.hasNext()) {
          Object methodName = var4.next();
          Properties methodAttrs = new Properties();
          methodAttrs.setProperty("name", (String)methodName);
          suiteBuffer.addEmptyElement("include", methodAttrs);
        }

        suiteBuffer.pop("methods");
        suiteBuffer.pop("class");
      } else {
        suiteBuffer.addEmptyElement("class", classAttrs);
      }

      suiteBuffer.pop("classes");
      suiteBuffer.pop("test");
    }
  }

  private abstract static class CustomizedSuite extends LaunchSuite {
    protected String m_projectName;
    protected String m_suiteName;
    protected Map<String, String> m_parameters;
    private XMLStringBuffer m_suiteBuffer;

    private CustomizedSuite(String projectName, String className, Map<String, String> parameters, String annotationType) {
      super(true);
      this.m_projectName = projectName;
      this.m_suiteName = className;
      this.m_parameters = parameters;
    }

    protected XMLStringBuffer createContentBuffer() {
      XMLStringBuffer suiteBuffer = new XMLStringBuffer();
      suiteBuffer.setDocType("suite SYSTEM \"https://testng.org/testng-1.0.dtd\"");
      Properties attrs = new Properties();
      attrs.setProperty("parallel", XmlSuite.ParallelMode.NONE.toString());
      attrs.setProperty("name", this.m_suiteName);
      suiteBuffer.push("suite", attrs);
      if (this.m_parameters != null) {
        Iterator var3 = this.m_parameters.entrySet().iterator();

        while(var3.hasNext()) {
          Map.Entry<String, String> entry = (Map.Entry)var3.next();
          Properties paramAttrs = new Properties();
          paramAttrs.setProperty("name", (String)entry.getKey());
          paramAttrs.setProperty("value", (String)entry.getValue());
          suiteBuffer.push("parameter", paramAttrs);
          suiteBuffer.pop("parameter");
        }
      }

      this.initContentBuffer(suiteBuffer);
      suiteBuffer.pop("suite");
      return suiteBuffer;
    }

    public XMLStringBuffer getSuiteBuffer() {
      if (null == this.m_suiteBuffer) {
        this.m_suiteBuffer = this.createContentBuffer();
      }

      return this.m_suiteBuffer;
    }

    protected abstract void initContentBuffer(XMLStringBuffer var1);

    public File save(File directory) {
      File suiteFile = new File(directory, "temp-testng-customsuite.xml");
      this.saveSuiteContent(suiteFile, this.getSuiteBuffer());
      return suiteFile;
    }

    protected void saveSuiteContent(File file, XMLStringBuffer content) {
      try {
        OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"));

        try {
          fw.write(content.getStringBuffer().toString());
        } catch (Throwable var7) {
          try {
            fw.close();
          } catch (Throwable var6) {
            var7.addSuppressed(var6);
          }

          throw var7;
        }

        fw.close();
      } catch (IOException var8) {
        IOException ioe = var8;
        LaunchSuite.LOGGER.error("IO Exception", ioe);
      }

    }
  }

  public static class ExistingSuite extends LaunchSuite {
    private File m_suitePath;

    public ExistingSuite(File path) {
      super(false);
      this.m_suitePath = path;
    }

    public XMLStringBuffer getSuiteBuffer() {
      throw new UnsupportedOperationException("Not implemented yet");
    }

    public File save(File directory) {
      return this.m_suitePath;
    }
  }
}
