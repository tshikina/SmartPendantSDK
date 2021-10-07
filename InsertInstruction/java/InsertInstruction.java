import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;

import yaskawa.ext.api.IllegalArgument;
import yaskawa.ext.api.ControllerEvent;
import yaskawa.ext.api.ControllerEventType;
import yaskawa.ext.api.CoordFrameRepresentation;
import yaskawa.ext.api.CoordinateFrame;
import yaskawa.ext.api.IntegrationPoint;
import yaskawa.ext.api.PendantEvent;
import yaskawa.ext.api.PendantEventType;
import yaskawa.ext.api.PredefinedCoordFrameType;
import yaskawa.ext.api.OrientationUnit;
import yaskawa.ext.api.VariableAddress;
import yaskawa.ext.api.Pendant.AsyncProcessor.property;
import yaskawa.ext.api.Scope;
import yaskawa.ext.api.AddressSpace;
import yaskawa.ext.api.Any;


import yaskawa.ext.*;
import yaskawa.ext.Pendant.PropValue;
import static yaskawa.ext.Pendant.propValue;

import java.util.*;



public class InsertInstruction {

    public InsertInstruction() throws TTransportException, IllegalArgument, Exception
    {
        var version = new Version(1,0,0);
        var languages = Set.of("en");

        extension = new Extension("com.yaskawa.yec.test.insertinstruction.extension",
                                version, "Yaskawa", languages,
                                "localhost", 10080);

        extension.copyLoggingToStdOutput = true;
        extension.info("Starting");

        System.out.println("API version: "+extension.apiVersion());
        pendant = extension.pendant();
        controller = extension.controller();
        System.out.println("Controller software version:"+controller.softwareVersion());

        extension.subscribeLoggingEvents();
    }

    protected Extension extension;
    protected Pendant pendant;
    protected Controller controller;

    class InstructionTest {
        String instruction;
        boolean expected = true;
        boolean actual = false;

        boolean tested = false;
        boolean result = false;
    }

    protected int m_maxLines = 25;
    protected int m_currentPageNo = 0;
    protected String m_currentCategoryName = "General";
    protected Map<String, List<InstructionTest>> m_instructionTestList = new HashMap<String, List<InstructionTest>>();

    public void run() throws TException, IOException
    {
        String yml = new String(Files.readAllBytes(Paths.get("InsertInstruction.yml")), StandardCharsets.UTF_8);
        var errors = pendant.registerYML(yml);
        if (errors.size() > 0) {
            System.out.println("YML Errors encountered:");
            for(var e : errors)
                System.out.println("  "+e);
        }


        pendant.registerUtilityWindow("ymlutil", "UtilWindow",
                                      "Test Insert Instruction", "Test Insert Instruction" );

        pendant.subscribeEventTypes(Set.of( 
            PendantEventType.UtilityOpened,
            PendantEventType.UtilityClosed,
            PendantEventType.Clicked
            ));

        extension.ping();


        pendant.addEventConsumer(PendantEventType.UtilityOpened, this::onUtilityOpened);
        pendant.addEventConsumer(PendantEventType.UtilityClosed, this::onUtilityClosed);

        pendant.addItemEventConsumer("generalTabButton", PendantEventType.Clicked, this::onTabSelected);
        pendant.addItemEventConsumer("motionTabButton", PendantEventType.Clicked, this::onTabSelected);
        pendant.addItemEventConsumer("ioTabButton", PendantEventType.Clicked, this::onTabSelected);
        pendant.addItemEventConsumer("mathTabButton", PendantEventType.Clicked, this::onTabSelected);
        pendant.addItemEventConsumer("controlTabButton", PendantEventType.Clicked, this::onTabSelected);
        pendant.addItemEventConsumer("macroTabButton", PendantEventType.Clicked, this::onTabSelected);

        pendant.addItemEventConsumer("testInstructionButton", PendantEventType.Clicked, this::onTestInstructionButtonClicked);
        pendant.addItemEventConsumer("testPageButton", PendantEventType.Clicked, this::onTestPageButtonClicked);
        pendant.addItemEventConsumer("clearResultButton", PendantEventType.Clicked, this::onClearResultButtonClicked);

        pendant.addItemEventConsumer("prevPageButton", PendantEventType.Clicked, this::onPrevPageButtonClicked);
        pendant.addItemEventConsumer("nextPageButton", PendantEventType.Clicked, this::onNextPageButtonClicked);

        extension.outputEvents = true;

        try {
            // run 'forever' (or until API service shutsdown)                                      
            extension.run(() -> false);
        } catch (Exception e) {
            System.out.println("Exception occured:"+e.toString());
        }
    }

    boolean isInstructionFileLoaded() {
        return m_instructionTestList.size() > 0;
    }

    void loadInstructionFile( String filePathStr ) {
        Path filePath = Paths.get(filePathStr);
        try {
            m_instructionTestList.clear();

            List<String> lines = Files.readAllLines(filePath);
            
            String currentCategory = "";
            m_instructionTestList.put(currentCategory, new ArrayList<InstructionTest>()); 
            for( var line : lines ){
                if( line.startsWith("#")) { // comment line
                    continue; // skip
                }
                else if( line.isBlank() ) {
                    continue; // skip
                }
                else if ( line.matches("^\\[(.*)\\]$")) { // category
                    currentCategory = line.replaceAll("^\\[(.*)\\]$", "$1");

                    if(!m_instructionTestList.containsKey(currentCategory)) {
                        m_instructionTestList.put(currentCategory, new ArrayList<InstructionTest>()); 
                    }
                }
                else {
                    var strings = line.split("\t");

                    InstructionTest instructionTest = new InstructionTest();

                    if( strings.length > 0){
                        instructionTest.instruction = strings[0]; // instruction
                    }
                    if( strings.length > 1){
                        instructionTest.expected = Boolean.parseBoolean(strings[1]); // expected result
                    }

                    if(!instructionTest.instruction.isBlank()) {
                        m_instructionTestList.get(currentCategory).add(instructionTest);
                    }
                }
            }
        }
        catch (Exception ex) {
        }

    }

    void showInstructionInCategory( String categoryName, int pageNo ) throws TTransportException, IllegalArgument, TException {
        var instructionTests = m_instructionTestList.get(categoryName);

        if( instructionTests != null ){
            String lineNoText = "";
            String instructionText = "";
            String expectedText = "";
            String actualText = "";
            String resultText = "";

            int maxPages = instructionTests.size() / m_maxLines;

            pageNo = Math.max(pageNo, 0);
            pageNo = Math.min(pageNo, maxPages);

            int beginIndex = pageNo * m_maxLines;
            int endIndex = Math.min( beginIndex + m_maxLines, instructionTests.size() );

            for( int i = beginIndex; i < endIndex; i++) {

                var test = instructionTests.get(i);
                lineNoText += String.format("%d\n", i);
                instructionText += String.format("%s\n", test.instruction);
                expectedText += String.format("%s\n", test.expected ? "Success" : "Failed");

                if( test.tested ) {
                    actualText += String.format("%s\n", test.actual ? "Success" : "Failed");
                    resultText += String.format("%s<br/>", test.result ? "Pass" : "<font color='red'>Failed</font>");
                }
                else {
                    actualText += "---\n";
                    resultText += "---<br/>";
                }
            }

            pendant.setProperties( List.of(
                propValue("lineNoText", "text", lineNoText),
                propValue("instructionText", "text", instructionText),
                propValue("expectedText", "text", expectedText),
                propValue("actualText", "text", actualText),
                propValue("resultText", "text", resultText),
                propValue("beginTextField", "text", String.format("%d", 0)),
                propValue("endTextField", "text", String.format("%d", instructionTests.size()-1)),
                propValue("pageText", "text", String.format("%d / %d", pageNo + 1, maxPages + 1))
            ));

            m_currentCategoryName = categoryName;
            m_currentPageNo = pageNo;
        }
    }

    void showTestResult() throws TTransportException, IllegalArgument, TException {
        var instructionTests = m_instructionTestList.get(m_currentCategoryName);

        if( instructionTests != null ){
            String actualText = "";
            String resultText = "";

            int beginIndex = m_currentPageNo * m_maxLines;
            int endIndex = Math.min( beginIndex + m_maxLines, instructionTests.size() );

            for( int i = beginIndex; i < endIndex; i++) {

                var test = instructionTests.get(i);

                if( test.tested ) {
                    actualText += String.format("%s\n", test.actual ? "Success" : "Failed");
                    resultText += String.format("%s<br/>", test.result ? "Pass" : "<font color='red'>Failed</font>");
                }
                else {
                    actualText += "---\n";
                    resultText += "---<br/>";
                }
            }

            pendant.setProperties( List.of(
                propValue("actualText", "text", actualText),
                propValue("resultText", "text", resultText)
            ));
        }
    }


    void showCurrentTestingLine( int lineNo ) throws TTransportException, IllegalArgument, TException {

        String lineNoText;

        if( lineNo >= 0 ){
            lineNoText = String.format("%d", lineNo);
        }
        else {
            lineNoText = "";
        }

        pendant.setProperty("currentTestLineText", "text", lineNoText);
    }

    void testInsertInstruction( int beginIndex, int endIndex ) throws TTransportException, IllegalArgument, TException {
        int testedCnt = 0;
        int passedCnt = 0;
        long lastUpdated = 0;

        // check screen
        var screenName = pendant.currentScreenName();
        if( screenName.compareTo("programmingViewScreen") != 0 ) {
            pendant.error("Programming View is not Opened", "Please open programming view to start test.");

            return;
        }


        // test
        var instructionTests = m_instructionTestList.get(m_currentCategoryName);

        beginIndex = Math.max(0, beginIndex);
        endIndex = Math.min( endIndex, instructionTests.size()-1 );

        for( int i = beginIndex; i <= endIndex; i++) {
            var test = instructionTests.get(i);

            System.out.print( String.format("Try insert [%d]: %s: ", i, test.instruction) );
            var resultText = pendant.insertInstructionAtSelectedLine(test.instruction);
            System.out.println( resultText );

            test.actual = (resultText.equals("Success"));
            test.result = (test.expected == test.actual);
            test.tested = true;

            // show current status
            long now = System.currentTimeMillis();
            if( now - lastUpdated > 1000) {
                lastUpdated = now;
                showCurrentTestingLine(i);
            }

            testedCnt++;
            if( test.result ) { 
                passedCnt++;
            }
        }

        showCurrentTestingLine(-1);
        showTestResult();

        pendant.notice("Test Completed", String.format("Test Insert Instruction is completed: %d/%d is passed.",  passedCnt, testedCnt));

    }

    void onUtilityOpened(PendantEvent e) 
    {
        try{
            if( !isInstructionFileLoaded() ) {
                loadInstructionFile("testInstructions.txt");
                showInstructionInCategory(m_currentCategoryName, 0);
            }
        }
        catch( Exception ex ){

        }
    }

    void onUtilityClosed(PendantEvent e) 
    {
        var props = e.getProps();
        if (props.get("identifier").toString() == "ymlutil") 
            System.out.println("Utility closed");
    }

    void onTabSelected(PendantEvent e)
    {
        try{
            var itemName = e.getProps().get("item").getSValue();

            String categoryName = "";
            switch( itemName )
            {
            case "generalTabButton":
                categoryName = "General";
                break;
            case "motionTabButton":
                categoryName = "Motion";
                break;
            case "ioTabButton":
                categoryName = "I/O";
                break;
            case "mathTabButton":
                categoryName = "Math";
                break;
            case "controlTabButton":
                categoryName = "Control";
                break;
            case "macroTabButton":
                categoryName = "Macro";
                break;
            }

            if( !categoryName.equals( m_currentCategoryName ) ) {
                m_currentPageNo = 0;
            }

            showInstructionInCategory(categoryName, m_currentPageNo);
        }
        catch( Exception ex ){

        }
    }

    void onTestInstructionButtonClicked(PendantEvent e) 
    {
        try {
            String beginText;
            String endText;
            beginText = pendant.property("beginTextField", "text").getSValue();
            endText = pendant.property("endTextField", "text").getSValue();

            int beginIndex = 0, endIndex = 0;

            beginIndex = Integer.parseInt( beginText );
            endIndex = Integer.parseInt( endText );

            testInsertInstruction(beginIndex, endIndex);
        }
        catch( Exception ex ){
            try {
                pendant.error("Insert Instruction Error", "Error is happen while inserting instruction: " + ex.toString());
            }
            catch( Exception eex ){}
        }
    }

    void onTestPageButtonClicked(PendantEvent e)
    {
        try {

            int beginIndex = m_currentPageNo * m_maxLines;
            int endIndex = beginIndex + m_maxLines;

            testInsertInstruction(beginIndex, endIndex);
        }
        catch( Exception ex ){
            try {
                pendant.error("Insert Instruction Error", "Error is happen while inserting instruction: " + ex.toString());
            }
            catch( Exception eex ){}
        }
    }

    void onClearResultButtonClicked(PendantEvent e) 
    {
        try {
            var instructionTests = m_instructionTestList.get(m_currentCategoryName);

            for( int i=0; i < instructionTests.size(); i++) {
                var test = instructionTests.get(i);

                test.tested = false;
            }

            showTestResult();
        }
        catch( Exception ex ){
        }
    }

    void onPrevPageButtonClicked(PendantEvent e)
    {
        try {
            showInstructionInCategory(m_currentCategoryName, m_currentPageNo - 1);
        }
        catch( Exception ex ){
        }
    }
    void onNextPageButtonClicked(PendantEvent e)
    {
        try {
            showInstructionInCategory(m_currentCategoryName, m_currentPageNo + 1);
        }
        catch( Exception ex ){
        }
    }


    public void close()
    {
        System.out.println("Closing");
    }

    public static void main(String[] args) {
        InsertInstruction thisExtension = null; 
        try {

            // launch
            try {
                thisExtension = new InsertInstruction();
            } catch (Exception e) {
                System.out.println("TestExtension failed to start, aborting: "+e.toString());
            }

            // run
            thisExtension.run();

        } catch (Exception e) {

            System.out.println("Exception: "+e.toString());        

        } finally {
            if (thisExtension != null)
                thisExtension.close();
        }
    }

}



