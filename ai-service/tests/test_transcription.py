import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
from pydantic import ValidationError
from meeting_agent.api import create_app
from meeting_agent.audio_tool import PreparedAudio, AudioReadError
from meeting_agent.transcription import LocalTranscriber, TranscriptionDraft, TranscriptionError, _SLOT
from meeting_agent.speech_runtime import SPEECH_JOB_TIMEOUT_SECONDS

class TranscriptionTests(unittest.TestCase):
    def setUp(self):
        self.meta = PreparedAudio(meeting_id='m1',audio_id='a1',sha256=hashlib.sha256(b'a').hexdigest(),byte_size=1,reported_duration_ms=None)
        self.data = dict(language='zh',duration_ms=1000,status='draft',text='测试',segments=[dict(segment_id='S1',start_ms=0,end_ms=900,text='测试',speaker_id=None,
            words=[dict(word_id='S1W1',start_ms=0,end_ms=400,text='测'),dict(word_id='S1W2',start_ms=400,end_ms=900,text='试')])])
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name); self.root=root
        for name in ('model.bin','config.json','tokenizer.json'): (root/name).write_text('fixture')
        self.env=patch.dict(os.environ,{'AICAP_STT_MODEL_DIR':str(root),'AICAP_LLM_API_KEY':'private-key'}); self.env.start(); self.addCleanup(self.env.stop)
    def worker(self, args, **kwargs):
        self.assertEqual(SPEECH_JOB_TIMEOUT_SECONDS,kwargs['timeout'])
        self.assertEqual('1',kwargs['env']['HF_HUB_OFFLINE'])
        self.assertEqual('1',kwargs['env']['TRANSFORMERS_OFFLINE'])
        self.assertEqual('1',kwargs['env']['PYTHONNOUSERSITE'])
        self.assertNotIn('AICAP_LLM_API_KEY',kwargs['env'])
        self.assertEqual(b'a',Path(args[4]).read_bytes())
        Path(args[5]).write_text(json.dumps(self.data),encoding='utf-8')
        return Mock(returncode=0)
    def invoke(self):
        with patch('meeting_agent.transcription.ROOT',self.root), patch('meeting_agent.transcription.subprocess.run',side_effect=self.worker):
            return LocalTranscriber().run(self.meta,b'a','zh')
    def test_worker_output_validated_and_temporary_audio_removed(self):
        result=self.invoke()
        self.assertEqual('not_saved',result.storage_status)
        self.assertEqual('not_available',result.diarization_status)
        self.assertTrue(result.requires_human_review)
        self.assertIsNone(result.segments[0].speaker_id)
        self.assertEqual('S1W2',result.segments[0].words[1].word_id)
        self.assertEqual([],list((self.root/'.stt-work').iterdir()))
    def test_invalid_timestamps_and_text_rejected(self):
        for key,value in (('start_ms',-1),('end_ms',1100),('segment_id','S2'),('speaker_id','member-7')):
            data=json.loads(json.dumps(self.data)); data['segments'][0][key]=value
            with self.assertRaises(ValidationError): TranscriptionDraft(audio=self.meta,**data)
        self.data['text']='different'
        with self.assertRaises(TranscriptionError): self.invoke()
        invalid=json.loads(json.dumps(self.data)); invalid['text']='测试'; invalid['segments'][0]['words'][1]['start_ms']=-1
        with self.assertRaises(ValidationError): TranscriptionDraft(audio=self.meta,**invalid)
    def test_silence_is_explicit_no_speech(self):
        self.data.update(status='no_speech',text='',segments=[])
        self.assertEqual('no_speech',self.invoke().status)
    def test_missing_model_does_not_spawn_worker(self):
        with patch.dict(os.environ,{'AICAP_STT_MODEL_DIR':str(self.root/'absent')}), patch('meeting_agent.transcription.subprocess.run') as worker:
            with self.assertRaises(TranscriptionError) as error: LocalTranscriber().run(self.meta,b'a')
            self.assertEqual(503,error.exception.status); worker.assert_not_called()
    def test_parallel_job_is_rejected_without_queue(self):
        _SLOT.acquire()
        try:
            with self.assertRaises(TranscriptionError) as error: self.invoke()
            self.assertEqual('stt_busy',error.exception.code)
        finally: _SLOT.release()
    def test_timeout_cleans_files_and_releases_slot(self):
        with patch('meeting_agent.transcription.ROOT',self.root), patch('meeting_agent.transcription.subprocess.run',side_effect=subprocess.TimeoutExpired('worker',SPEECH_JOB_TIMEOUT_SECONDS)):
            with self.assertRaises(TranscriptionError) as error: LocalTranscriber().run(self.meta,b'a')
            self.assertEqual(504,error.exception.status)
        self.assertEqual([],list((self.root/'.stt-work').iterdir()))
        self.assertEqual('draft',self.invoke().status)
    def test_worker_errors_do_not_echo_details(self):
        for code,status in (('invalid_audio',422),('audio_duration_exceeded',413),('private-path',502)):
            self.data={'error':code}
            with self.assertRaises(TranscriptionError) as error: self.invoke()
            self.assertEqual(status,error.exception.status)
            self.assertNotIn('private',error.exception.code)
    def test_non_object_and_invalid_utf8_worker_outputs_are_bounded(self):
        def output(value):
            def worker(args,**kwargs):
                Path(args[5]).write_bytes(value)
                return Mock(returncode=0)
            with patch('meeting_agent.transcription.ROOT',self.root), patch('meeting_agent.transcription.subprocess.run',side_effect=worker):
                with self.assertRaises(TranscriptionError) as error: LocalTranscriber().run(self.meta,b'a')
                self.assertEqual('invalid_stt_result',error.exception.code)
        output(b'[]')
        output(b'\xff')
    def test_api_auth_read_before_inference_and_no_identity_guess(self):
        reader=Mock(); reader.read.return_value=(self.meta,b'a')
        engine=Mock(); engine.run.return_value=TranscriptionDraft(audio=self.meta,**self.data)
        with patch('meeting_agent.api.AudioReadTool',return_value=reader), patch('meeting_agent.api.LocalTranscriber',return_value=engine), TestClient(create_app()) as client:
            path='/api/meetings/m1/transcription/run'; headers={'Authorization':'Bearer fixture-token'}
            self.assertEqual(401,client.post(path,json={'audio_id':'a1'}).status_code)
            self.assertEqual(422,client.post(path,headers=headers,json={'audio_id':'a1','speaker_id':7}).status_code)
            engine.run.assert_not_called()
            response=client.post(path,headers=headers,json={'audio_id':'a1','language':'zh'})
            self.assertEqual(200,response.status_code,response.text)
            engine.run.assert_called_once_with(self.meta,b'a','zh')
            reader.read.side_effect=AudioReadError('transcription_forbidden',403)
            self.assertEqual(403,client.post(path,headers=headers,json={'audio_id':'a1'}).status_code)
            self.assertEqual(1,engine.run.call_count)
