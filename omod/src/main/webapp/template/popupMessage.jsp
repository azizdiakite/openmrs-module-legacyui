<%@ include file="/WEB-INF/view/module/legacyui/template/include.jsp" %>
		<br/>
		</div>
	</div>

	<div id="footer" 
		xmlns:c="http://java.sun.com/jsp/jstl/core"
		xmlns:fn="http://java.sun.com/jsp/jstl/functions"
		xmlns:spring="http://www.springframework.org/tags">
	
		<div id="footerInner">
		
			<openmrs:extensionPoint pointId="org.openmrs.footerFullBeforeStatusBar" type="html" />
		
			<c:if test="${not empty popupMessage}">
				<span id="extraData">${popupMessage}</span>
			</c:if>

			<span id="poweredBy"><a href="http://openmrs.org"><openmrs:message code="footer.poweredBy"/> <img border="0" align="top" src="<%= request.getContextPath() %>/moduleResources/legacyui/images/openmrs_logo_tiny.png"/></a></span>
		</div>
	</div>

</body>
</html>
