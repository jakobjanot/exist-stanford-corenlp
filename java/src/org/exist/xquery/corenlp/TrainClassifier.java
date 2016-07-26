/*
 *   exist-stanford-corenlp: XQuery module to integrate the Stanford CoreNLP
 *   annotation pipeline library with eXist-db.
 *   Copyright (C) 2016 ljo
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.exist.xquery.corenlp;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.WordToSentenceProcessor;
import edu.stanford.nlp.sequences.SeqClassifierFlags;
import edu.stanford.nlp.sequences.ColumnDocumentReaderAndWriter;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.apache.poi.POITextExtractor;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlException;

import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.QName;
import org.exist.dom.memtree.DocumentBuilderReceiver;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.security.PermissionDeniedException;
import org.exist.util.Configuration;
import org.exist.util.ParametersExtractor;
import org.exist.util.io.Resource;
import org.exist.util.io.TemporaryFileManager;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.*;
import org.exist.xquery.corenlp.util.DefaultBinaryValueManager;
import org.exist.xquery.value.*;
import org.xml.sax.SAXException;

import org.jopendocument.dom.ODPackage;
import org.jopendocument.dom.ODDocument;
import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;

public class TrainClassifier extends BasicFunction {
    private final static Logger LOG = LogManager.getLogger(TrainClassifier.class);

    public final static FunctionSignature signatures[] = {
            new FunctionSignature(
                new QName("train-classifier-spreadsheet-doc", StanfordCoreNLPModule.NAMESPACE_URI, StanfordCoreNLPModule.PREFIX),
                "Train a CRF classifier based on anntations in the provided spreadsheet document. Returns a serialiezed model to use in CRF classification.",
                new SequenceType[] {
                    new FunctionParameterSequenceType("classifier", Type.STRING, Cardinality.ZERO_OR_ONE,
                        "The fully qualified name of an alternative classifier to load. Must be available on the classpath."),
		    new FunctionParameterSequenceType("configuration", Type.ELEMENT, Cardinality.EXACTLY_ONE,
                                "The training configuration, e.g. &lt;parameters&gt;&lt;param name='inputFormat' value='ods'/&gt;&lt;param name='backgroundSymbol' value='O'/&gt;&lt;param name='wordCol' value='0'/&gt;&lt;param name='answerCol' value='1'/&gt;&lt;param name='tagCol' value='2'/&gt;&lt;/parameters&gt;. Available input formats ods (default), xlsx, xls or tsv."),
                        new FunctionParameterSequenceType("uploaded-file", Type.BASE64_BINARY, Cardinality.ZERO_OR_ONE,
                                "The uploaded file with your annotated text to train a classifier from. If no file is posted in the request you need to provide localFilePath in the configuration parameter.")
                },
                new FunctionReturnSequenceType(Type.ITEM, Cardinality.EXACTLY_ONE,
                        "The trained serialised CRF classifier")
            )
    };

    private Path tempInFile = null;
    private Path tempOutFile = null;
    private BinaryValueFromBinaryString uploadedFileBase64String = null;
    private AnalyzeContextInfo cachedContextInfo;
    private Properties parameters = new Properties();
    private enum InputDocType {ODS, XLSX, XLS, TSV};
    private InputDocType inputFormat = InputDocType.ODS;
    private boolean gzipOutput = true;
    private String classifierClassPath = null;
    private String backgroundSymbol = "O";
    private String localFilePath = null;
    private int wordCol = 0;
    private int answerCol = 1;
    private int tagCol = -1;

    public TrainClassifier(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
        cachedContextInfo = new AnalyzeContextInfo(contextInfo);
        super.analyze(cachedContextInfo);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        context.pushDocumentContext();
        try {
	    Collection<List<CoreLabel>> documents;
	    if (!args[0].isEmpty()) {
		classifierClassPath = args[0].getStringValue();
	    }

	    if (!args[1].isEmpty()) {
		parameters = ParametersExtractor.parseParameters(((NodeValue)args[1].itemAt(0)).getNode());
	    }

	    if (!args[2].isEmpty()) {
		uploadedFileBase64String = new BinaryValueFromBinaryString(new Base64BinaryValueType(), args[2].getStringValue());
	    }
	    tempInFile = TemporaryFileManager.getInstance().getTemporaryFile();
	    tempOutFile = TemporaryFileManager.getInstance().getTemporaryFile();

	    for (String property : parameters.stringPropertyNames()) {
		if ("inputFormat".equals(property)) {
		    String value = parameters.getProperty(property);
		    if ("ods".equals(value)) {
			inputFormat = InputDocType.ODS;
		    } else if ("xlsx".equals(value)) {
			inputFormat = InputDocType.XLSX;
		    } else if ("xls".equals(value)) {
			inputFormat = InputDocType.XLS;
		    } else if ("tsv".equals(value)) {
			inputFormat = InputDocType.TSV;
		    }
		} else if ("outputFormat".equals(property)) {
		    String value = parameters.getProperty(property);
		    gzipOutput = value.endsWith("gz") ? true : false;
		} else if ("backgroundSymbol".equals(property)) {
		    String value = parameters.getProperty(property);
		    backgroundSymbol = value;
		} else if ("localFilePath".equals(property)) {
		    String value = parameters.getProperty(property);
		    localFilePath = value;
		} else if ("wordCol".equals(property)) {
		    String value = parameters.getProperty(property);
		    wordCol = Integer.valueOf(value);
		} else if ("answerCol".equals(property)) {
		    String value = parameters.getProperty(property);
		    answerCol = Integer.valueOf(value);
		} else if ("tagCol".equals(property)) {
		    String value = parameters.getProperty(property);
		    tagCol = Integer.valueOf(value);
		} 
	    }
 
	    documents = readSpreadsheet(inputFormat);

	    BinaryValueManager bvm = new DefaultBinaryValueManager(context);
	    Base64BinaryDocument bvfis = null;
	    if (documents.isEmpty()) {
		LOG.error("No annotated text extracted from the spreadsheet document!");
		throw new XPathException(this, "No annotated text extracted from the spreadsheet document!");
	    } else {
		trainClassifier(documents, inputFormat);
		bvfis = Base64BinaryDocument.getInstance(bvm, Files.newInputStream(tempOutFile));
	    }
	    return bvfis;
        } catch (IOException ioe) {
	    throw new XPathException(this, "Error while reading text document: " + ioe.getMessage(), ioe);
        } finally {
            context.popDocumentContext();
	    if (tempInFile != null) {
		TemporaryFileManager.getInstance().returnTemporaryFile(tempInFile);
	    }
	    if (tempOutFile != null) {
		TemporaryFileManager.getInstance().returnTemporaryFile(tempOutFile);
	    }

        }
    }

    private void trainClassifier(Collection<List<CoreLabel>> documents, final InputDocType inputFormat) throws XPathException {
	final Properties props = new Properties();
	// fixme! - check ocrTrain configurable under other name?
	//props.setProperty("ocrTrain", "true");
	//props.setProperty("serializeTo", tempOutFile.toAbsolutePath().toString());
	props.setProperty("useClassFeature", "true");
	props.setProperty("useWord", "true");
	props.setProperty("useNGrams", "true");
	props.setProperty("noMidNGrams", "true");
	props.setProperty("useDisjunctive", "true");
	props.setProperty("maxNGramLeng", "6");
	props.setProperty("usePrev", "true");
	props.setProperty("useNext", "true");
	props.setProperty("useSequences", "true");
	props.setProperty("usePrevSequences", "true");
	props.setProperty("maxLeft", "1");
	props.setProperty("useTypeSeqs", "true");
	props.setProperty("useTypeSeqs2", "true");
	props.setProperty("useTypeySequences", "true");
	props.setProperty("wordShape", "chris2useLC");

	CRFClassifier<CoreLabel> classifier = new CRFClassifier(props);
        classifier.train(documents, new ColumnDocumentReaderAndWriter());
	if (gzipOutput) {
	    try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(
			new GZIPOutputStream(Files.newOutputStream(tempOutFile))))) {
	    classifier.serializeClassifier(oos);
	    } catch (IOException ioe) {
		throw new XPathException(this, "Unable to write gzipped serialized classifier to temp file: " + ioe.getMessage(), ioe);
	    }
	} else {
	    classifier.serializeClassifier(tempOutFile.toAbsolutePath().toString());
	}
    }

    private Collection<List<CoreLabel>> readSpreadsheet(final InputDocType inputFormat) throws XPathException {
	Collection<List<CoreLabel>> res = null;
	if (uploadedFileBase64String == null && localFilePath == null) {
	    res = readODSSpreadsheet("/db/temp/swe-clarin/user-annotated.ods");
	} else {
	    switch(inputFormat) {
	    case ODS:
		res = readODSSpreadsheet(localFilePath);
		break;
	    case XLSX:
		res = readXLSXSpreadsheet(localFilePath, inputFormat);
		break;
	    case XLS:
		res = readXLSXSpreadsheet(localFilePath, inputFormat);
		break;
	    case TSV:
		res = readTSVSpreadsheet(localFilePath);
		break;
	    }
	}
	return res;
    }

    private Collection<List<CoreLabel>> readODSSpreadsheet(final String localFilePath) throws XPathException {
	Collection<List<CoreLabel>> documents = new ArrayList<>();
	List<CoreLabel> document = new ArrayList<>();
	SpreadSheet spreadSheet = null;

	//try (InputStream is = Files.newInputStream(tempInFile)) {
	try (InputStream is = uploadedFileBase64String != null ? uploadedFileBase64String.getInputStream() : new Resource(localFilePath).getInputStream()) {
	    spreadSheet = ODPackage.createFromStream(is, "UserAnnotatedDocument").getSpreadSheet();
	} catch (IOException ioe) {
	    throw new XPathException(this, "Error while reading spreadsheet document: " + ioe.getMessage(), ioe);
	}

	Sheet sheet = spreadSheet.getSheet(0);
	    
	for (int i = 0; i < sheet.getRowCount(); i++) {
	    CoreLabel tok = new CoreLabel();
	    String value1 = sheet.getValueAt(0, i).toString();
	    String value2 = sheet.getValueAt(1, i).toString();

	    tok.setWord(value1);
	    tok.setNER(value2);
	    tok.set(CoreAnnotations.AnswerAnnotation.class, value2);
	    if (sheet.getColumnCount() > 2) {
		String value3 = sheet.getValueAt(2, i).toString();
		if (!"".equals(value3) && tagCol > -1) {
		    tok.setTag(value3);
		}
	    }

	    if (!"".equals(value1)) {
		document.add(tok);
	    } else {
		documents.add(document);
		document = new ArrayList<>();
	    }
	}
	if (document.size() > 0) {
	    documents.add(document);
	}
	return documents;
    }

    private Collection<List<CoreLabel>> readXLSXSpreadsheet(final String localFilePath, final InputDocType inputFormat) throws XPathException {
	Workbook workbook = null;
	Collection<List<CoreLabel>> documents = new ArrayList<>();
	List<CoreLabel> document = new ArrayList<>();

	// try (InputStream is = Files.newInputStream(tempInFile)) {
	try (InputStream is = uploadedFileBase64String != null ? uploadedFileBase64String.getInputStream() : new Resource(localFilePath).getInputStream()) {
	    if (inputFormat == InputDocType.XLSX) {
		workbook = new XSSFWorkbook(is);
	    } else {
		workbook = new HSSFWorkbook(is);
	    }
	} catch (FileNotFoundException fe) {
	    LOG.error(fe);
	} catch (IOException ioe) {
	    LOG.error(ioe);
	    throw new XPathException(this, "Error while reading spreadsheet document: " + ioe.getMessage(), ioe);
	}
	org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
	Row row;
	Cell cell;
	for (int rowPos = 0; rowPos <= sheet.getLastRowNum(); rowPos++) {
	    CoreLabel tok = new CoreLabel();
	    row = (Row) sheet.getRow(rowPos);
	    if (row != null) {
		for (int cellPos = 0; cellPos < row.getLastCellNum(); cellPos++) {
		    cell = row.getCell(cellPos, Row.CREATE_NULL_AS_BLANK);
		    switch (cellPos) {
		    case 0:
			if (cell != null && cell.getCellType() == Cell.CELL_TYPE_STRING) {
			    tok.setWord(cell == null ? "" : cell.getStringCellValue());
			} else if(cell != null && cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
			    tok.setWord(cell == null ? "" : cell.getNumericCellValue() + "");
			}

			break;
		    case 1:
			tok.setNER(cell == null ? "" : cell.getStringCellValue());
			tok.set(CoreAnnotations.AnswerAnnotation.class, cell == null ? "O" : cell.getStringCellValue());
			break;
		    case 2:
			tok.setTag(cell == null ? "" : cell.getStringCellValue());
			break;
		    default: break;
		    }
		}
	    }
	    if (row != null && !"".equals(tok.word())) {
		document.add(tok);
	    } else {
		documents.add(document);
		document = new ArrayList<>(); 
	    }
	}
	if (document.size() > 0) {
	    documents.add(document);
	}
	return documents;
    }

    private Collection<List<CoreLabel>> readTSVSpreadsheet(final String localFilePath) throws XPathException {
	String separator = "\t";
	String line;
	Collection<List<CoreLabel>> documents = new ArrayList<>();
	List<CoreLabel> document = new ArrayList<>();

	//try (BufferedReader tsv = Files.newBufferedReader(tempInFile)) {
	try (BufferedReader tsv = uploadedFileBase64String != null ? new BufferedReader(new InputStreamReader(uploadedFileBase64String.getInputStream(), "UTF-8")) : new Resource(localFilePath).getBufferedReader()) {
	    while ((line = tsv.readLine()) != null) {
		CoreLabel tok = new CoreLabel();
		List<String> cells = Arrays.asList(line.split(separator));
		if (cells.size() > 0 && !"".equals(cells.get(0))) {
		    tok.setWord(cells.get(0));
		    tok.setNER(cells.get(1));
		    tok.set(CoreAnnotations.AnswerAnnotation.class, cells.get(1));
		    if (cells.size() > 2 && !"".equals(cells.get(2))) {
			tok.setTag(cells.get(2));
		    }
		    document.add(tok);
		} else {
		    documents.add(document);
		    document = new ArrayList<>();
		}
	    }
	    if (document.size() > 0) {
		documents.add(document);
	    }
	} catch (IOException ioe) {
	    LOG.error(ioe);
	    throw new XPathException(this, "Error while reading spreadsheet document: " + ioe.getMessage(), ioe);
	}
	return documents;
    }

}
