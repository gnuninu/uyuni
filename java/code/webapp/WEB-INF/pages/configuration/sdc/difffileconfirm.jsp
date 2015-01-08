<%@ taglib uri="http://rhn.redhat.com/rhn" prefix="rhn" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib uri="http://struts.apache.org/tags-html" prefix="html" %>
<%@ taglib uri="http://struts.apache.org/tags-bean" prefix="bean" %>


<html>
<head>
    <meta name="name" value="sdc.config.jsp.header" />
</head>
<body>
<%@ include file="/WEB-INF/pages/common/fragments/systems/system-header.jspf" %>

<rhn:toolbar base="h2" icon="header-system" >
  <bean:message key="sdcdiffconfirm.jsp.header"
                arg0="${fn:escapeXml(system.name)}"/>
</rhn:toolbar>

  <div class="page-summary">
    <p>
    <bean:message key="sdcdiffconfirm.jsp.summary"
                  arg0="${fn:escapeXml(system.name)}"/>
    </p>
  </div>

<html:form method="post"
                action="/systems/details/configuration/DiffFileConfirmSubmit.do?sid=${system.id}">
    <rhn:csrf />
    <c:set var="button" value="sdcdiffconfirm.jsp.schedule" />

    <rhn:list pageList="${requestScope.pageList}"
            noDataText="sdcconfigfiles.jsp.noFiles">

      <rhn:listdisplay filterBy="sdcconfigfiles.jsp.filename">
        <%@ include file="/WEB-INF/pages/common/fragments/configuration/sdc/configfile_rows.jspf" %>
      </rhn:listdisplay>
    </rhn:list>

    <c:if test="${not empty requestScope.pageList}">
        <p><bean:message key="sdcconfigconfirm.jsp.widgetsummary" /></p>
        <jsp:include page="/WEB-INF/pages/common/fragments/datepicker-with-label.jsp">
            <jsp:param name="widget" value="date" />
            <jsp:param name="label_text" value="sdcconfigconfirm.jsp.usedate" />
        </jsp:include>
        <div class="text-right">
            <hr />
            <html:submit styleClass="btn btn-primary" property="dispatch">
                <bean:message key="${button}" />
            </html:submit>
        </div>
    </c:if>
</html:form>

</body>
</html>
