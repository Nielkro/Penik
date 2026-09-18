export namespace main {
	
	export class CaptureSource {
	    id: string;
	    name: string;
	    type: string;
	    thumbnail: string;
	    width: number;
	    height: number;
	
	    static createFrom(source: any = {}) {
	        return new CaptureSource(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.type = source["type"];
	        this.thumbnail = source["thumbnail"];
	        this.width = source["width"];
	        this.height = source["height"];
	    }
	}
	export class FileFilter {
	    displayName: string;
	    pattern: string;
	
	    static createFrom(source: any = {}) {
	        return new FileFilter(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.displayName = source["displayName"];
	        this.pattern = source["pattern"];
	    }
	}
	export class HttpBinaryResponse {
	    status: number;
	    statusText: string;
	    headers: Record<string, string>;
	    bodyBase64: string;
	
	    static createFrom(source: any = {}) {
	        return new HttpBinaryResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.status = source["status"];
	        this.statusText = source["statusText"];
	        this.headers = source["headers"];
	        this.bodyBase64 = source["bodyBase64"];
	    }
	}
	export class HttpResponse {
	    status: number;
	    statusText: string;
	    headers: Record<string, string>;
	    body: string;
	
	    static createFrom(source: any = {}) {
	        return new HttpResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.status = source["status"];
	        this.statusText = source["statusText"];
	        this.headers = source["headers"];
	        this.body = source["body"];
	    }
	}
	export class VersionInfo {
	    version: string;
	    platform: string;
	    deviceName: string;
	    serverUrl: string;
	
	    static createFrom(source: any = {}) {
	        return new VersionInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.version = source["version"];
	        this.platform = source["platform"];
	        this.deviceName = source["deviceName"];
	        this.serverUrl = source["serverUrl"];
	    }
	}

}

