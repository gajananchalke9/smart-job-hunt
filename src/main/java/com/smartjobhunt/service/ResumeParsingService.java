package com.smartjobhunt.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Extracts plain text from a PDF file using Apache PDFBox.
 *
 * <p>The extracted text is used as input to the Gemini scoring prompt, enabling
 * the model to reason about the candidate's skills and experience.
 */
@Service
public class ResumeParsingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeParsingService.class);

    /**
     * Extracts all text from a PDF multipart upload.
     *
     * @param file the PDF file uploaded by the client
     * @return the full plain-text content of the PDF
     * @throws IOException if the file cannot be read or is not a valid PDF
     */
    public String extractText(MultipartFile file) throws IOException {
        log.info("Starting PDF text extraction - filename: {}, size: {} bytes",
                file.getOriginalFilename(), file.getSize());
        
        // PDFBox 3.x uses Loader.loadPDF(byte[]) instead of PDDocument.load(InputStream)
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            int pageCount = document.getNumberOfPages();
            log.debug("PDF loaded successfully - pageCount: {}", pageCount);
            
            PDFTextStripper stripper = new PDFTextStripper();
            // Sort by position so extracted text reads top-to-bottom, left-to-right
            stripper.setSortByPosition(true);
            
            log.debug("Extracting text from PDF using PDFBox");
            String extractedText = stripper.getText(document);
            
            log.info("PDF text extraction completed - extracted {} characters from {} pages",
                    extractedText.length(), pageCount);
            
            return extractedText;
        } catch (IOException e) {
            log.error("Failed to extract text from PDF: {}", e.getMessage(), e);
            throw e;
        }
    }
}
