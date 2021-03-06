package edu.stanford.bmir.icd.claml;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;

import edu.stanford.bmir.whofic.IcdIdGenerator;
import edu.stanford.bmir.whofic.icd.ICDContentModel;
import edu.stanford.smi.protege.model.Project;
import edu.stanford.smi.protege.util.CollectionUtilities;
import edu.stanford.smi.protege.util.IDGenerator;
import edu.stanford.smi.protege.util.Log;
import edu.stanford.smi.protegex.owl.model.OWLModel;
import edu.stanford.smi.protegex.owl.model.RDFResource;
import edu.stanford.smi.protegex.owl.model.RDFSClass;
import edu.stanford.smi.protegex.owl.model.RDFSNamedClass;


/**
 * Class that imports a CLAML file. 
 * 
 * The class imports the CLAML content into the ICD content model.
 * 
 * TODO: add also the public ids to newly created classes
 * 
 * 
 * @author ttania
 *
 */
public class ClamlImport {
	private static transient Logger log = Log.getLogger(ClamlImport.class);

	private OWLModel owlModel;
	private ICDContentModel cm;

	// it is only one, but we need it as a collection
	private Collection<String> topClsColl;
	private String defaultNamespace;

	private Map<RDFResource, String> termToRefCode = new HashMap<RDFResource, String>();
	private Map<RDFSNamedClass, List<String>> cls2superclsesNames = new HashMap<RDFSNamedClass, List<String>>();

	private Collection<RDFSNamedClass> visitedClses = new ArrayList<RDFSNamedClass>();
	
	/**
	 * CLAML programmatic import. 
	 * 
	 * @param args - (1) Path to PPRJ file into which to import;
	 * 				 (2) Path to CLAML file to import;
	 * 			     (3) Name of top class to import under;
	 *               (4) Default namespace for imported entities.
	 */
	public static void main(String[] args) {
		if (args.length < 4) {
			System.out.println("Expected 4 arguments: "
					+ "(1) Path to PPRJ file into which to import; "
					+ "(2) Path to CLAML file to import; "
					+ "(3) Name of top class to import under; "
					+ "(4) Default namespace for imported entities.");
			System.exit(1);
		}

		// load into a file that has the empty content model in it
		Project prj = Project.loadProjectFromFile(args[0], new ArrayList());
		OWLModel owlModel = (OWLModel) prj.getKnowledgeBase();

		long t0 = System.currentTimeMillis();
		ClamlImport ci = new ClamlImport(owlModel);
		
		log.info("Starting CLAML import");
		
		ci.doImport(new File(args[1]), args[2], args[3]);

		log.info("Saving of OWL file on " + new Date());
		
		prj.save(new ArrayList());

		log.info("Finished CLAML import in " + ((System.currentTimeMillis() - t0) / 1000) + " seconds");
	}

	public ClamlImport(OWLModel owlModel) {
		this.owlModel = owlModel;
		this.cm = new ICDContentModel(this.owlModel);
	}

	/**
	 * Method that does the actual import of the clamlFile given as argument
	 * 
	 * @param clamlFile - the CLAML file to import
	 * @param defaultNamespace - default namespace to be added to all imported entities
	 * @param topCls    - top class to import under, null means owl:Thing
	 */
	public void doImport(File clamlFile, String topClsName, String defaultNamespace) {

		this.topClsColl = getTopCls(topClsName);
		this.defaultNamespace = defaultNamespace;

		boolean generateEventsEnabled = owlModel.getGenerateEventsEnabled();
		owlModel.setGenerateEventsEnabled(false);

		try {
			log.info("Started importing of CLAML file: " + clamlFile.getAbsolutePath() + " on " + new Date());
			long t0 = System.currentTimeMillis();

			parse(clamlFile);
			postprocess();
			cleanup();

			log.info("Finished importing CLAML file in " + ((System.currentTimeMillis() - t0) / 1000) + " seconds");
		} finally {
			owlModel.setGenerateEventsEnabled(generateEventsEnabled);
		}
	}

	private Collection<String> getTopCls(String topClsName) {
		if (topClsName != null) {
			RDFSNamedClass cls = owlModel.getRDFSNamedClass(topClsName);
			if (cls != null) {
				return CollectionUtilities.createCollection(topClsName);
			}
		}
		log.warning("Could not find top class: " + topClsName + ". Using owl:Thing as top class.");
		return CollectionUtilities.createCollection(owlModel.getOWLThingClass().getName());
	}

	private void parse(File file) {
		SAXBuilder builder = new SAXBuilder();
		try {
			Document doc = builder.build(file);
			Element root = doc.getRootElement();

			// parse classes
			for (Iterator iterator = root.getChildren(ClamlConstants.CLASS_ELEMENT).iterator(); iterator.hasNext();) {
				Element el = (Element) iterator.next();
				parseElement(el);
			}

			// parse modifiers
			for (Iterator iterator = root.getChildren(ClamlConstants.MODIFIER_ELEMENT).iterator(); iterator.hasNext();) {
				Element el = (Element) iterator.next();
				parseElement(el);
			}

			// parse modifier classes
			for (Iterator iterator = root.getChildren(ClamlConstants.MODIFIER_CLASS_ELEMENT).iterator(); iterator
					.hasNext();) {
				Element el = (Element) iterator.next();
				parseElement(el);
			}

		} catch (JDOMException e) {
			log.log(Level.SEVERE, "Error at parsing CLAML file: " + e.getMessage(), e);
		} catch (IOException e) {
			log.log(Level.SEVERE,
					"Could not open CLAML file: " + file.getAbsolutePath() + " Error message:" + e.getMessage(), e);
		}
	}

	private void parseElement(Element el) {
		RDFSNamedClass cls = parseCls(el);
		parseRubrics(cls, el);
	}

	private RDFSNamedClass parseCls(Element el) {
	
		String code = el.getAttributeValue(ClamlConstants.CODE_ATTR);

		String clsName = defaultNamespace + code;
		
		//the class might exist already from an imported ontology, e.g., the content model
		RDFSNamedClass cls = owlModel.getRDFSNamedClass(clsName);
		
		if (cls == null) {
			// create it under the top cls, fix parents in post-processing
			// this is needed to create the appropriate metaclasses
			
			cls = cm.createICDCategory(clsName, topClsColl);
		}
		
		//TODO: this adds the icdCode, but it may not be the right one for all classifications
		cls.addPropertyValue(cm.getIcdCodeProperty(), code);

		List superClsElems = el.getChildren(ClamlConstants.SUPERCLASS_ELEMENT);

		List<String> superclsesNames = new ArrayList<>();

		for (Iterator iterator = superClsElems.iterator(); iterator.hasNext();) {
			Element classChild = (Element) iterator.next();
			String supercls = classChild.getAttributeValue(ClamlConstants.CODE_ATTR);
			superclsesNames.add(supercls);
		}

		cls2superclsesNames.put(cls, superclsesNames);

		return cls;
	}

	private void parseRubrics(RDFSNamedClass cls, Element el) {
		List rubricChildren = el.getChildren(ClamlConstants.RUBRIC_ELEMENT);
		for (Iterator iterator = rubricChildren.iterator(); iterator.hasNext();) {
			Element rubricChild = (Element) iterator.next();
			parseRubric(cls, rubricChild);
		}
	}

	private void parseRubric(RDFSNamedClass cls, Element rubricChild) {
		String id = rubricChild.getAttributeValue(ClamlConstants.ID_ATTR);
		String kind = rubricChild.getAttributeValue(ClamlConstants.KIND_ATTR);
		Element labelElement = rubricChild.getChild(ClamlConstants.LABEL_ELEMENT);
		if (kind.equals(ClamlConstants.RUBRIC_KIND_PREFFERD_ATTR)) {
			parsePreferred(cls, id, labelElement);
			// parsePreferred2(cls, id, labelElement); //preferred is already
			// translated as icdTitle
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_INCLUSION_ATTR)) {
			parseInclusion(cls, id, labelElement);
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_EXCLUSION_ATTR)) {
			parseExclusion(cls, id, labelElement);
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_CODING_HINT_ATTR)) {
			parseCodingHint(cls, id, labelElement);
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_INTRODUCTION_ATTR)) {
			parseIntroduction(cls, id, labelElement);
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_NOTE_ATTR)) {
			parseNote(cls, id, labelElement);
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_PREFFERD_LONG_ATTR)) {
			parsePreferredLong(cls, id, labelElement);
		} else if ( kind.equals(ClamlConstants.RUBRIC_KIND_DEFINITION_ATTR) ||
				    kind.equals(ClamlConstants.RUBRIC_KIND_DESCRIPTION_ATTR) ) {
			parseDefinition(cls, id, labelElement);
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_RELATED_IMPAIRMENT_ATTR)) {
			parseRelatedImpairment(cls, id, labelElement);
		} else if (kind.equals(ClamlConstants.RUBRIC_KIND_REMARK_ATTR)) {
			parseRemark(cls, id, labelElement);
		}
	}

	private void parseRemark(RDFSNamedClass cls, String id, Element labelElement) {
		String label = labelElement.getTextTrim();
		label = label.trim();
		if (label.length() == 0) {
			return;
		}
		
		RDFResource term = cm.createTerm(cm.getTermRemarkClass());
		
		String lang = labelElement.getAttributeValue(ClamlConstants.XML_LANG, Namespace.XML_NAMESPACE);
		cm.fillTerm(term, id, label, lang);
		
		cls.addPropertyValue(cm.getRemarkProperty(), term);
	}

	private void parseRelatedImpairment(RDFSNamedClass cls, String id, Element labelElement) {
		String label = labelElement.getTextTrim();
		label = label.trim();
		if (label.length() == 0) {
			return;
		}
		
		RDFResource term = cm.createTerm(cm.getTermRelatedImpairmentClass());
		
		String lang = labelElement.getAttributeValue(ClamlConstants.XML_LANG, Namespace.XML_NAMESPACE);
		cm.fillTerm(term, id, label, lang);
		
		cls.addPropertyValue(cm.getRelatedImpairmentProperty(), term);
	}

	private void parseDefinition(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getTermDefinitionClass());
		parseLabel(cls, term, id, labelElement);
		cm.addDefinitionTermToClass(cls, term);
	}

	private void parsePreferred(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getTermTitleClass());
		
		labelElement.setText(getProperCaseTitle(labelElement.getTextTrim().trim()));
		
		parseLabel(cls, term, id, labelElement);
		
		cm.addTitleTermToClass(cls, term);
		// add also the rdfs:label as the title for BioPortal
		cm.addRdfsLabel(cls, false);
	}

	private void parseInclusion(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getTermBaseInclusionClass());
		parseLabel(cls, term, id, labelElement);
		cm.addBaseInclusionTermToClass(cls, term);
		// needs to be added also to baseIndex because of the current CM.. 
		// TT does not like this
		cm.addBaseIndexTermToClass(cls, term);
	}

	private void parseExclusion(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getTermBaseExclusionClass());
		parseLabel(cls, term, id, labelElement); // make sure here that you also get the references
		cm.addBaseExclusionTermToClass(cls, term);
	}

	private void parseCodingHint(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getICD10NotesClass());
		parseLabel(cls, term, id, labelElement);
		cm.addCodingHintToClass(cls, term);
	}

	private void parseIntroduction(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getICD10NotesClass());
		parseLabel(cls, term, id, labelElement);
		cm.addIntroductionToClass(cls, term);
	}

	private void parseNote(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getICD10NotesClass());
		parseLabel(cls, term, id, labelElement);
		cm.addNotesToClass(cls, term);
	}

	private void parsePreferredLong(RDFSNamedClass cls, String id, Element labelElement) {
		RDFResource term = cm.createTerm(generateId(), cm.getTermTitleClass());
		parseLabel(cls, term, id, labelElement);
		cm.addPreferredLongToClass(cls, term);
	}

	private void parseLabel(RDFSNamedClass cls, RDFResource term, String id, Element labelElement) {
		String label = labelElement.getTextTrim();
		label = label.replace("()", "");
		label = label.replace("(-)", "");
		label = label.trim();

		String lang = labelElement.getAttributeValue(ClamlConstants.XML_LANG, Namespace.XML_NAMESPACE);
		cm.fillTerm(term, id, label, lang);
		
		List refElems = labelElement.getChildren(ClamlConstants.REFERENCE_ELEMENT);
		
		if (refElems.size() == 1) {
			parseSingleRefElement(term, (Element) refElems.iterator().next());
		} else if (refElems.size() == 2) {
			if (labelElement.getTextTrim().contains("(-)")) {
				parseRangeRef(term,refElems);
			} else {
				parseMultipleRefElems(term, refElems);
			}
		} else if (refElems.size() > 2) {
			parseMultipleRefElems(term, refElems);
		}
		
		// add rdfs:label to terms for BioPortal
		cm.addRdfsLabelToTerm(term, label, lang);
	}


	private void parseSingleRefElement(RDFResource term, Element refElement) {
		RDFResource ref = cm.createTerm(generateId(), cm.getClamlReferencesClass());
		
		String code = refElement.getAttributeValue(ClamlConstants.CODE_ATTR);
		String usage = refElement.getAttributeValue(ClamlConstants.USAGE_ATTR);
		String text = refElement.getTextTrim();
		
		if (text != null) {
			text = text.trim();
		}
		
		cm.fillClamlReference(ref, text, usage, code);
		cm.addClamlRefToTerm(term, ref);

		termToRefCode.put(term, text);
	}
	
	private void parseMultipleRefElems(RDFResource term, List refElems) {
		for (Iterator iterator = refElems.iterator(); iterator.hasNext();) {
			Object next = iterator.next();
			if (next instanceof Element) {
				Element refElement = (Element) next;
				parseSingleRefElement(term, refElement);
			}
		}
	}
	
	private void parseRangeRef(RDFResource term, List refElems) {
		RDFResource ref = cm.createTerm(generateId(), cm.getClamlReferencesClass());
		
		Element el1 = (Element) refElems.get(0);
		String text1 = el1.getTextTrim().trim();
		
		Element el2 = (Element) refElems.get(1);
		String text2 = el2.getTextTrim().trim();
		
		String text = text1 + "-" + text2;
		
		cm.fillClamlReference(ref, text, (String) null, (String) null);
		cm.addClamlRefToTerm(term, ref);

		termToRefCode.put(term, text);
	}
	
	

	private String generateId() {
		if (defaultNamespace.length() == 0) {
			return IcdIdGenerator.getNextUniqueId(owlModel);
		}
		return defaultNamespace + IDGenerator.getNextUniqueId();
	}
	
	private void postprocess() {
		addSuperClses();
		addResidualFlagsAndTitles();
		//has to come after addSuperClses() and addResidualFlagsAndTitles()
		removeResidualClses(); 
		addReferencedCategories();
		fixMetaclasses();
		addSiblingOrdering();
	}


	private void removeResidualClses() {
		log.info("Removing residual classes..");
		
		Collection<RDFSNamedClass> residuals = new ArrayList<RDFSNamedClass>();
		
		for (RDFSNamedClass cls : cls2superclsesNames.keySet()) {
			if (isResidual(cls)) {
				residuals.add(cls);
			}
		}

		for (RDFSNamedClass residualCls : residuals) {
			log.info("--- Removing residual: " + residualCls.getLabels() + ". " + residualCls);
			cls2superclsesNames.remove(residualCls);
			deleteResidual(residualCls);
		}
	}

	private void addResidualFlagsAndTitles() {
		List<String> processed = new ArrayList<String>();
		for (List<String> superClsNames : cls2superclsesNames.values()) {
			for (String superClsName : superClsNames) {
				if (processed.contains(superClsName)) {
					continue;
				}
				RDFSNamedClass superCls = owlModel.getRDFSNamedClass(defaultNamespace + superClsName);
				
				Collection<RDFSNamedClass> subclses = getNamedChildren(superCls);
				RDFSNamedClass otherResidual = getOtherResidual(subclses);
				RDFSNamedClass unspecResidual = getUnspecResidual(subclses);
				
				addOtherResidualFlagAndTitle(superCls, otherResidual);
				addUnspecifiedResidualFlagAndTitle(superCls, unspecResidual);
				
				processed.add(superClsName);
			}
		}
	}

	
	
	private RDFSNamedClass getUnspecResidual(Collection<RDFSNamedClass> subclses) {
		for (RDFSNamedClass subcls : subclses) {
			String subClsTitle = cm.getTitleLabel(subcls);
			if (subcls.getName().endsWith("9") && 
					subClsTitle.contains("unspecified")) {
				return subcls;
			}
		}
		return null;
	}

	private RDFSNamedClass getOtherResidual(Collection<RDFSNamedClass> subclses) {
		for (RDFSNamedClass subcls : subclses) {
			String subClsTitle = cm.getTitleLabel(subcls);
			if (subcls.getName().endsWith("8") && 
					subClsTitle.contains("other specified")) {
				return subcls;
			}
		}
		return null;
	}
	

	private void addOtherResidualFlagAndTitle(RDFSNamedClass superCls, RDFSNamedClass otherResidual) {
		if (otherResidual == null) {
			//suppress other residual
			superCls.addPropertyValue(cm.getSuppressOtherSpecifiedResidualsProperty(), Boolean.TRUE);
		} else {
			superCls.addPropertyValue(cm.getSuppressOtherSpecifiedResidualsProperty(), Boolean.FALSE);
			
			//if it exists, check title
			String otherResTitle = cm.getTitleLabel(otherResidual);
			String goodTitle = cm.getTitleLabel(superCls) + ", other specified";
			
			if (goodTitle.equalsIgnoreCase(otherResTitle) == false) {
				RDFResource titleTerm = cm.createTitleTerm();
				titleTerm.setPropertyValue(cm.getLabelProperty(), otherResTitle);
				titleTerm.setPropertyValue(cm.getLangProperty(), "en");
				superCls.addPropertyValue(cm.getOtherSpecifiedResidualTitleProperty(), titleTerm);
			}
		}
	}
	
	private void addUnspecifiedResidualFlagAndTitle(RDFSNamedClass superCls, RDFSNamedClass unspecifiedResidual) {
		if (unspecifiedResidual == null) {
			//suppress unspecified residual
			superCls.addPropertyValue(cm.getSuppressUnspecifiedResidualsProperty(), Boolean.TRUE);
		} else {
			superCls.addPropertyValue(cm.getSuppressUnspecifiedResidualsProperty(), Boolean.FALSE);
			
			//if it exists, check title
			String unspecResTitle = cm.getTitleLabel(unspecifiedResidual);
			String goodTitle = cm.getTitleLabel(superCls) + ", unspecified";
			
			if (goodTitle.equalsIgnoreCase(unspecResTitle) == false) {
				RDFResource titleTerm = cm.createTitleTerm();
				titleTerm.setPropertyValue(cm.getLabelProperty(), unspecResTitle);
				titleTerm.setPropertyValue(cm.getLangProperty(), "en");
				superCls.addPropertyValue(cm.getUnspecifiedResidualTitleProperty(), titleTerm);
			}
		}
	}
	
	private void deleteResidual(RDFSNamedClass cls) {
		RDFResource titleInst = cm.getTerm(cls, cm.getIcdTitleProperty());
		titleInst.delete();
		
		cls.delete();
	}

	//FIXME: This might be ICF specific
	private boolean isResidual(RDFSNamedClass cls) {
		String title = cm.getTitleLabel(cls);
		title = title.toLowerCase();
		
		String icdCode = (String) cls.getPropertyValue(cm.getIcdCodeProperty());
		icdCode = icdCode == null ? "" : icdCode;
		
		return (icdCode.endsWith("8") || icdCode.endsWith("9")) &&
				(title.contains("other specified") || title.contains("unspecified")
						|| title.contains(", specified"));    //this last one is for handling typos, such as in ICF codes b7209 and s76009
	}

	private void addSuperClses() {
		log.info("Adding superclasses..");

		RDFSNamedClass topCls = owlModel.getRDFSNamedClass(CollectionUtilities.getFirstItem(topClsColl));

		for (RDFSNamedClass cls : cls2superclsesNames.keySet()) {
			List<String> superclsesNames = cls2superclsesNames.get(cls);
			for (String superclsName : superclsesNames) {
				RDFSNamedClass superCls = owlModel.getRDFSNamedClass(defaultNamespace + superclsName);
				if (superCls == null) {
					log.warning(
							"Could not add superclass to class: " + cls + ". Superclass not found: " + superclsName);
				} else {
					cls.addSuperclass(superCls);
					cls.removeSuperclass(topCls);
				}
			}
		}
	}

	private void addReferencedCategories() {
		log.info("Adding referenced categories..");

		for (RDFResource term : termToRefCode.keySet()) {
			String code = termToRefCode.get(term);

			try {
				RDFSNamedClass refCls = owlModel.getRDFSNamedClass(defaultNamespace + code);
				if (refCls == null) {
					log.warning("Could not find referenced class: " + code + ". Referee: " + owlModel.getReferences(term, 0));
				} else {
					term.setPropertyValue(cm.getReferencedCategoryProperty(), refCls);
					
					//remove ref label if the same as ref class title
					String refTitle = cm.getTitleLabel(refCls);
					String termLabel = (String) term.getPropertyValue(cm.getLabelProperty());
					
					if (refTitle.trim().equalsIgnoreCase(termLabel.trim())) {
						term.removePropertyValue(cm.getLabelProperty(), termLabel);
					}
				}

			} catch (Exception e) {
				log.log(Level.SEVERE, "Could not add reference category: " + code, e);
			}
		}
	}
	
	private void fixMetaclasses() {
		log.info("Fixing metaclasses..");
		RDFSNamedClass topCls = owlModel.getRDFSNamedClass(CollectionUtilities.getFirstItem(topClsColl));
		fixMetaclasses(topCls);
	}

	private void fixMetaclasses(RDFSNamedClass parentCls) {
		if (visitedClses.contains(parentCls)) {
			return;
		}
		
		visitedClses.add(parentCls);
		
		for (RDFSClass cls : (Collection<RDFSClass>) parentCls.getSubclasses(false)) {
			if (cls instanceof RDFSNamedClass) {
				fixMetaclasses(parentCls, (RDFSNamedClass) cls);
			}
		}
	}

	private void fixMetaclasses(RDFSNamedClass parentCls, RDFSNamedClass cls) {
		log.fine("Fix metaclasses for: " + cls);
		Collection<RDFSNamedClass> parentTypes = parentCls.getRDFTypes();
		Collection<RDFSNamedClass> childTypes = cls.getRDFTypes();
		for (RDFSNamedClass parentType : parentTypes) {
			if (childTypes.contains(parentType) == false) {
				log.fine("--- Add type: " + parentType);
				cls.addRDFType(parentType);
			}
		}
		fixMetaclasses(cls);
	}
	
	private void addSiblingOrdering() {
		log.info("Adding sibling ordering..");
		RDFSNamedClass topCls = owlModel.getRDFSNamedClass(CollectionUtilities.getFirstItem(topClsColl));
		
		visitedClses.clear();
		addSiblingOrdering(topCls);
	}

	private void addSiblingOrdering(RDFSNamedClass parentCls) {
		if (visitedClses.contains(parentCls)) {
			return;
		}
		
		visitedClses.add(parentCls);
		
		log.fine("Add sibling order to: " + parentCls);
		
		List<RDFSNamedClass> children = getNamedChildren(parentCls);
		children.sort(getCodeComparator());
		
		for (RDFSNamedClass child : children) {
			cm.addChildToIndex(parentCls, child, true);
			addSiblingOrdering(child);
		}
	}

	private List<RDFSNamedClass> getNamedChildren(RDFSNamedClass parentCls) {
		List<RDFSClass> children = (List<RDFSClass>) parentCls.getSubclasses(false);
		List<RDFSNamedClass> namedChildren = new ArrayList<RDFSNamedClass>();
		for (RDFSClass child : children) {
			if (child instanceof RDFSNamedClass) {
				namedChildren.add((RDFSNamedClass) child);
			}
		}
		return namedChildren;
	}
	
	private Comparator<RDFSNamedClass> getCodeComparator() {
		return new Comparator<RDFSNamedClass>() {

			@Override
			public int compare(RDFSNamedClass c1, RDFSNamedClass c2) {
				String code1 = (String) c1.getPropertyValue(cm.getIcdCodeProperty());
				String code2 = (String) c2.getPropertyValue(cm.getIcdCodeProperty());
				
				if (code1 == null && code2 == null) {
					return 0;
				}
				
				if (code1 == null) {
					return 1;
				}
				
				if (code2 == null) {
					return -1;
				}
				
				return code1.compareTo(code2);
			}
		};
	}

	private String getProperCaseTitle(String title) {
		if (title.length() > 1 && isUpperCase(title)) {
			title = title.charAt(0) + title.substring(1).toLowerCase();
		}
		
		return title;
	}
	
	private boolean isUpperCase(String str) {
		for (int i=0; i< str.length(); i++) {
			if (Character.isWhitespace(str.charAt(i)) == false && 
					Character.isLowerCase(str.charAt(i))) {
				return false;
			}
		}
		return true;
	}
	
	private void cleanup() {
		termToRefCode.clear();
		cls2superclsesNames.clear();
		visitedClses.clear();
	}

}
