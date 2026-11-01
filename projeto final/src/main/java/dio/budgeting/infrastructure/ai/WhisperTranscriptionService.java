package dio.budgeting.infrastructure.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Transcrição de áudio usando um Whisper 100% local e gratuito
 * (container openai-whisper-asr-webservice), no lugar do Whisper da OpenAI.
 *
 * Fluxo: áudio (m4a/webm/mp3/wav...) → POST /asr → texto em português.
 */
@Service
public class WhisperTranscriptionService {

    private final RestClient restClient;

    public WhisperTranscriptionService(@Value("${app.whisper.base-url:http://localhost:9030}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Recebe o arquivo de áudio e devolve o texto transcrito em português. */
    public String transcribe(Resource audio) {
        var bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("audio_file", audio);

        var text = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/asr")
                        .queryParam("task", "transcribe")
                        .queryParam("language", "pt")
                        .queryParam("output", "txt")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(bodyBuilder.build())
                .retrieve()
                .body(String.class);

        return text == null ? "" : text.trim();
    }
}
