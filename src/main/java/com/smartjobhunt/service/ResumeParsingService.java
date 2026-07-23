package com.smartjobhunt.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

    /**
     * Extracts all text from a PDF multipart upload.
     *
     * @param file the PDF file uploaded by the client
     * @return the full plain-text content of the PDF
     * @throws IOException if the file cannot be read or is not a valid PDF
     */
    public String extractText(MultipartFile file) throws IOException {
        // PDFBox 3.x uses Loader.loadPDF(byte[]) instead of PDDocument.load(InputStream)
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Sort by position so extracted text reads top-to-bottom, left-to-right
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
}
